// Dootah composition of xprem's MIT services. No upstream router or EE modules.
package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"
	"xprem/config"
	"xprem/internal/bucket"
	"xprem/internal/bucketmigration"
	_ "xprem/internal/bucketmigrations"
	"xprem/internal/database"
	"xprem/internal/database/postgres"
	"xprem/internal/database/postgres/migrations"
	"xprem/internal/handlers"
	"xprem/internal/helpers"
	"xprem/internal/metrics"
	"xprem/internal/middleware"
	"xprem/internal/repository"
	"xprem/internal/services"

	"github.com/gorilla/mux"
)

func main() {
	provision := flag.Bool("provision", false, "create one app, branch, channel and publisher credential; print JSON to a private file")
	migrate := flag.Bool("migrate", false, "explicitly migrate database and storage")
	health := flag.Bool("health", false, "probe local readiness")
	appName := flag.String("app-name", "", "app name and code-signing certificate common name")
	channel := flag.String("channel", "development", "initial channel and branch")
	keyApp := flag.String("key-app", "", "existing app UUID for API key management")
	keyAction := flag.String("key-action", "list", "list, create or revoke")
	keyID := flag.String("key-id", "", "key ID to revoke after binding rotation")
	flag.Parse()
	if *health {
		client := http.Client{Timeout: 4 * time.Second}
		r, err := client.Get("http://127.0.0.1:" + config.GetPort() + "/ready")
		if err != nil {
			os.Exit(1)
		}
		r.Body.Close()
		if r.StatusCode != 200 {
			os.Exit(1)
		}
		return
	}
	production := os.Getenv("DOOTAH_MODE") == "production"
	if os.Getenv("DOOTAH_REQUIRE_PRODUCTION") == "true" && !production {
		die("DOOTAH_MODE must explicitly be production")
	}
	if mode := os.Getenv("DOOTAH_MODE"); mode != "" && mode != "development" && mode != "production" {
		die("DOOTAH_MODE")
	}
	for _, name := range []string{"DB_URL", "DB_KEYS_MASTER_KEY_B64", "JWT_SECRET", "ADMIN_PASSWORD"} {
		if file := os.Getenv(name + "_FILE"); file != "" {
			if os.Getenv(name) != "" {
				die(name + " ambiguous input")
			}
			info, err := os.Stat(file)
			if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 {
				die(name + "_FILE must be private")
			}
			raw, err := os.ReadFile(file)
			if err != nil {
				die(name + "_FILE unreadable")
			}
			os.Setenv(name, strings.TrimSpace(string(raw)))
		}
	}
	if production {
		u, err := url.Parse(os.Getenv("BASE_URL"))
		if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || u.Path != "" || u.RawQuery != "" || u.Fragment != "" {
			die("BASE_URL requires HTTPS origin")
		}
		d, err := url.Parse(os.Getenv("DB_URL"))
		if err != nil || (d.Scheme != "postgresql" && d.Scheme != "postgres") || d.Host == "" || d.User == nil {
			die("DB_URL")
		}
		pw, ok := d.User.Password()
		if !ok || len(pw) < 24 {
			die("DB_URL credential")
		}
		key, err := base64.StdEncoding.DecodeString(os.Getenv("DB_KEYS_MASTER_KEY_B64"))
		if err != nil || len(key) != 32 {
			die("DB_KEYS_MASTER_KEY_B64")
		}
		if len(os.Getenv("JWT_SECRET")) < 32 {
			die("JWT_SECRET")
		}
		if *migrate && (len(os.Getenv("ADMIN_PASSWORD")) < 24 || os.Getenv("ADMIN_EMAIL") == "") {
			die("ADMIN_EMAIL / ADMIN_PASSWORD")
		}
		if *provision && (len(*appName) < 1 || len(*appName) > 128 || len(*channel) < 1 || len(*channel) > 64) {
			die("app-name / channel")
		}
		// Upstream log messages can contain request-derived values. Production emits
		// only composition-owned structured lifecycle events, never raw upstream errors.
		log.SetOutput(io.Discard)
	}
	if *migrate && *provision {
		die("choose migrate or provision")
	}

	config.LoadConfig()
	if !config.IsDBMode() || config.GetEnv("STORAGE_MODE") != "local" || config.IsBundleDiffingEnabled() {
		die("PostgreSQL, local storage and bundle diffing disabled required")
	}
	if err := config.ValidateMasterKey(); err != nil {
		die("xprem operation failed")
	}
	metrics.InitMetrics()
	if !production || *migrate {
		if err := bucketmigration.EnsureMigrations(); err != nil {
			die("storage migration failed")
		}
	}
	ctx := context.Background()
	db, err := database.NewPostgresEngine(ctx, database.LoadDBConfigFromEnv())
	if err != nil {
		die("xprem operation failed")
	}
	defer db.Close()
	migrations.SetEngine(db)
	if !production || *migrate {
		postgres.RunDBMigrations(config.GetDBURL())
	}
	if *migrate {
		fmt.Fprintln(os.Stderr, `{"event":"xprem_migrated"}`)
		return
	}
	b := bucket.GetBucket()
	ready := func(ctx context.Context) bool {
		var version int64
		if err := db.DB.QueryRow(ctx, "SELECT max(version_id) FROM goose_db_version WHERE is_applied").Scan(&version); err != nil || version != 20260913120000 {
			return false
		}
		pending, err := bucketmigration.Pending(b)
		return err == nil && len(pending) == 0
	}
	if production {
		var privileged bool
		err := db.DB.QueryRow(ctx, `SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls OR has_schema_privilege(current_user,'public','CREATE') OR has_database_privilege(current_user,current_database(),'CREATE') FROM pg_roles WHERE rolname=current_user`).Scan(&privileged)
		if err != nil || privileged || !ready(ctx) {
			die("runtime database privileges or migrations")
		}
	}
	apps := repository.NewPostgresAppRepository(db)
	branches := repository.NewPostgresBranchRepository(db)
	channels := repository.NewPostgresChannelRepository(db)
	updates := repository.NewPostgresUpdateRepository(db)
	rollouts := repository.NewPostgresRolloutRepository(db)
	auth := services.NewCliAuthService(repository.NewPostgresAuthRepository(db))
	appService := services.NewAppService(apps)
	branchService := services.NewBranchService(branches, channels, updates, rollouts, b.UpdateStore, b.PatchStore)
	channelService := services.NewChannelService(branches, channels)
	if *keyApp != "" {
		cert, err := appService.RetrieveAppCertificate(ctx, *keyApp)
		if err != nil {
			die("key app unavailable")
		}
		switch *keyAction {
		case "list":
			keys, err := auth.GetApiKeysMetadata(ctx, *keyApp)
			if err != nil {
				die("key list failed")
			}
			json.NewEncoder(os.Stdout).Encode(keys)
		case "create":
			key, err := auth.GenerateAPIKey(ctx, *keyApp, "Dootah publisher rotation")
			if err != nil {
				die("key creation failed")
			}
			json.NewEncoder(os.Stdout).Encode(map[string]string{"appId": *keyApp, "apiKey": key, "certificate": cert})
		case "revoke":
			if err := auth.RevokeApiKey(ctx, *keyApp, *keyID); err != nil {
				die("key revocation failed")
			}
		default:
			die("key-action")
		}
		return
	}
	if *provision {
		id, err := appService.CreateApp(ctx, provisionName(*appName), config.KeysConfig{Mode: config.KeysModeDatabase})
		if err != nil {
			die("xprem operation failed")
		}
		branch := *channel
		if _, err = branchService.CreateBranch(ctx, id, branch); err != nil {
			die("xprem operation failed")
		}
		if _, err = channelService.CreateChannel(ctx, id, &branch, branch); err != nil {
			die("xprem operation failed")
		}
		key, err := auth.GenerateAPIKey(ctx, id, "Dootah publisher")
		if err != nil {
			die("xprem operation failed")
		}
		cert, err := appService.RetrieveAppCertificate(ctx, id)
		if err != nil {
			die("xprem operation failed")
		}
		if err = json.NewEncoder(os.Stdout).Encode(map[string]string{"appId": id, "branch": branch, "channel": branch, "apiKey": key, "certificate": cert}); err != nil {
			die("xprem operation failed")
		}
		return
	}
	updateService := services.NewUpdateService(updates)
	deployment := services.NewDeploymentService(branchService, updateService, updates, b.BlobStore, b.UpdateStore, nil)
	protocol := handlers.NewExpoProtocolHandler(services.NewExpoProtocolService(apps, channels, updates, updateService, services.DefaultBranchRules(), b.BlobStore, b.PatchStore))
	upload := handlers.NewUploadHandler(deployment)
	r := mux.NewRouter()
	r.HandleFunc("/manifest", protocol.HandleManifest).Methods("GET")
	r.HandleFunc("/assets", protocol.HandleAssets).Methods("GET")
	r.HandleFunc("/live", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusOK) }).Methods("GET")
	r.HandleFunc("/ready", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
		defer cancel()
		if !ready(ctx) {
			w.WriteHeader(503)
			return
		}
		w.WriteHeader(200)
	}).Methods("GET")
	publish := r.PathPrefix("/{APP_ID}").Subrouter()
	publish.Use(middleware.AppResolverMiddleware(apps))
	publish.Use(publisherAuth(auth))
	// Operator validation is private and requires the app-scoped publisher key.
	publish.HandleFunc("/cloudBinding", func(w http.ResponseWriter, r *http.Request) {
		id := mux.Vars(r)["APP_ID"]
		cert, err := appService.RetrieveAppCertificate(r.Context(), id)
		if err != nil {
			http.Error(w, "binding unavailable", 503)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"appId": id, "certificate": cert})
	}).Methods("GET")
	// Private Cloud adapter: isolate each release from public latest-update selection.
	publish.HandleFunc("/cloudChannel/{BRANCH}", func(w http.ResponseWriter, r *http.Request) {
		id, branch := mux.Vars(r)["APP_ID"], mux.Vars(r)["BRANCH"]
		if !strings.HasPrefix(branch, "cloud-") || len(branch) != 42 {
			http.Error(w, "invalid Cloud channel", http.StatusBadRequest)
			return
		}
		knownBranches, err := branchService.GetBranches(r.Context(), id)
		if err != nil {
			http.Error(w, "lookup failed", 503)
			return
		}
		found := false
		for _, item := range knownBranches {
			if item.BranchName == branch {
				found = true
			}
		}
		if !found {
			if _, err := branchService.CreateBranch(r.Context(), id, branch); err != nil {
				http.Error(w, "channel creation failed", http.StatusConflict)
				return
			}
		}
		knownChannels, err := channelService.GetChannels(r.Context(), id)
		if err != nil {
			http.Error(w, "lookup failed", 503)
			return
		}
		for _, item := range knownChannels {
			if item.ReleaseChannelName == branch {
				w.WriteHeader(http.StatusOK)
				return
			}
		}
		if _, err := channelService.CreateChannel(r.Context(), id, &branch, branch); err != nil {
			http.Error(w, "channel creation failed", http.StatusConflict)
			return
		}
		w.WriteHeader(http.StatusCreated)
	}).Methods("POST")
	publish.HandleFunc("/requestUploadUrl/{BRANCH}", upload.RequestUploadUrlHandler).Methods("POST")
	publish.HandleFunc("/markUpdateAsUploaded/{BRANCH}", upload.MarkUpdateAsUploadedHandler).Methods("POST")
	publish.HandleFunc("/uploadLocalFile", upload.RequestUploadLocalFileHandler).Methods("PUT")
	publish.HandleFunc("/rollback/{BRANCH}", handlers.NewRollbackHandler(deployment).HandleRollback).Methods("POST")
	publish.HandleFunc("/republish/{BRANCH}", handlers.NewRepublishHandler(deployment).HandleRepublish).Methods("POST")
	server := &http.Server{Addr: net.JoinHostPort(config.GetBindAddress(), config.GetPort()), Handler: r, ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 2 * time.Minute, WriteTimeout: 2 * time.Minute, IdleTimeout: 60 * time.Second}
	shutdownContext, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	drained := make(chan struct{})
	go func() {
		<-shutdownContext.Done()
		deadline, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		server.Shutdown(deadline)
		close(drained)
	}()
	fmt.Fprintln(os.Stderr, `{"event":"xprem_listening"}`)
	if err := server.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
		die("HTTP listen failed")
	}
	<-drained
}

// Community API keys grant publishing/rollback for their entire app. The upstream
// service checks app ownership; the upload handler independently validates tokens.
func publisherAuth(auth *services.CliAuthService) mux.MiddlewareFunc {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			credential, err := auth.AuthenticateCliCredential(r.Context(), mux.Vars(r)["APP_ID"], helpers.GetAuth(r))
			if err != nil {
				http.Error(w, "publisher credential rejected", http.StatusUnauthorized)
				return
			}
			if credential.KeyID == 0 {
				http.Error(w, "database API key required", http.StatusUnauthorized)
				return
			}
			next.ServeHTTP(w, r.WithContext(services.WithCliAuth(r.Context(), credential)))
		})
	}
}

func die(name string) {
	fmt.Fprintln(os.Stderr, `{"event":"xprem_failed","check":`+strconv.Quote(name)+`}`)
	os.Exit(1)
}
func provisionName(name string) string {
	if name == "" {
		return "Dootah"
	}
	return name
}
