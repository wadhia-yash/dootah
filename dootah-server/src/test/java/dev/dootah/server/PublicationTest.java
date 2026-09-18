package dev.dootah.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dootah.contract.SignedUpdate;
import dev.dootah.contract.SignedImage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PublicationTest {
    @TempDir Path dir;
    ObjectMapper mapper = new ObjectMapper();
    KeyPair key; ReleaseCatalog catalog; PublicationStore store; MockMvc api;
    String token = "test-token-".repeat(4), origin = "https://localhost:8443";
    byte[] image = new byte[]{1,2,3}; // Publication verifies bytes; Android still verifies native image decoding.
    byte[] bundle; String hash, imageHash;
    @BeforeEach void setup() throws Exception {
        key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ObjectNode root = mapper.createObjectNode();
        root.putObject("publishers").put("example.app", Base64.getEncoder().encodeToString(Arrays.copyOfRange(key.getPublic().getEncoded(),12,44)));
        root.putArray("releases"); mapper.writeValue(dir.resolve("catalog.json").toFile(), root);
        catalog = new ReleaseCatalog(dir.resolve("catalog.json").toString(),mapper);
        store = new PublicationStore(catalog,mapper,dir.resolve("objects").toString(),origin);
        api = MockMvcBuilders.standaloneSetup(new PublicationController(store,mapper,token),new UpdateController(catalog)).build();
        imageHash = PublicationStore.digest(image);
        bundle = ("// dootah-images:"+imageHash+"\nvar works = true;").getBytes(); hash = PublicationStore.digest(bundle);
    }
    ObjectNode release(int version, int percent) throws Exception {
        var update = new SignedUpdate(1,"example.app","9",version,true,origin+"/artifacts/"+hash,hash,
                List.of(new SignedImage(imageHash,origin+"/artifacts/"+imageHash,imageHash)));
        Signature signer=Signature.getInstance("Ed25519");signer.initSign(key.getPrivate());signer.update(update.signingBytes());
        var item=mapper.createObjectNode().put("channel","production").put("rolloutPercent",percent).put("paused",false);
        item.set("manifest",mapper.readTree(update.manifestJson(Base64.getEncoder().encodeToString(signer.sign()))));return item;
    }
    void uploadAll() throws Exception {store.upload(hash,new ByteArrayInputStream(bundle));store.upload(imageHash,new ByteArrayInputStream(image));}
    byte[] state() throws Exception {return Files.readAllBytes(catalog.path());}
    @Test void completePublishIsOnlyVisibleAfterRegistration() throws Exception {
        var r=release(1,100); uploadAll(); assertTrue(catalog.read().isEmpty());
        api.perform(post("/publish/releases").header("Authorization","Bearer "+token).content(r.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("registered"));
        assertEquals(1,catalog.read().size());
        api.perform(get("/artifacts/"+hash)).andExpect(status().isOk()).andExpect(content().bytes(bundle));
    }
    @Test void missingBundleNeverChangesCatalog() throws Exception {var before=state();assertThrows(NoSuchFileException.class,()->store.register(release(1,100)));assertArrayEquals(before,state());}
    @Test void missingAssetNeverChangesCatalog() throws Exception {store.upload(hash,new ByteArrayInputStream(bundle));var before=state();assertThrows(NoSuchFileException.class,()->store.register(release(1,100)));assertArrayEquals(before,state());}
    @Test void wrongUploadHashRejectedWithoutObject() throws Exception {
        api.perform(put("/publish/artifacts/"+hash).header("Authorization","Bearer "+token).content(image)).andExpect(status().isBadRequest());
        assertFalse(Files.exists(dir.resolve("objects").resolve(hash)));assertTrue(catalog.read().isEmpty());
    }
    @Test void partialUploadAndLostRegistrationResponseRetrySafely() throws Exception {
        store.upload(hash,new ByteArrayInputStream(bundle));assertThrows(NoSuchFileException.class,()->store.register(release(1,100)));
        uploadAll();var r=release(1,100);var first=store.register(r);var again=store.register(r);
        assertEquals("registered",first.get("status"));assertEquals("existing",again.get("status"));
        assertEquals(first.get("identity"),again.get("identity"));assertEquals(1,catalog.read().size());
    }
    @Test void conflictingVersionRejectedAndCurrentProductionUnchanged() throws Exception {
        uploadAll();store.register(release(1,100));var before=state();
        bundle=("// dootah-images:"+imageHash+"\nchanged;").getBytes();hash=PublicationStore.digest(bundle);uploadAll();
        assertThrows(PublicationStore.Conflict.class,()->store.register(release(1,100)));assertArrayEquals(before,state());
    }
    @Test void registrationIoFailureLeavesHealthyProductionUnchanged() throws Exception {
        uploadAll();store.register(release(1,100));var before=state();
        Path lock=catalog.path().resolveSibling("catalog.json.lock");Files.delete(lock);Files.createDirectory(lock);
        assertThrows(IOException.class,()->store.register(release(2,100)));assertArrayEquals(before,state());
        assertEquals(1,catalog.read().get(0).version());
        Files.delete(lock);assertEquals("registered",store.register(release(2,100)).get("status"));
    }
    @Test void invalidSignatureCannotPublish() throws Exception {
        uploadAll();var r=release(1,100);((ObjectNode)r.get("manifest")).put("enabled",false);
        assertThrows(IllegalArgumentException.class,()->store.register(r));assertTrue(catalog.read().isEmpty());
    }
    @Test void rolloutChangesNoArtifactOrSignatureAndRetryDoesNotResetIt() throws Exception {
        uploadAll();var r=release(1,0);var identity=store.register(r).get("identity");var signed=catalog.read().get(0).manifest();
        api.perform(put("/publish/releases/"+identity+"/rollout").header("Authorization","Bearer "+token)
                .content("{\"rolloutPercent\":100,\"paused\":true}")).andExpect(status().isOk());
        store.register(r);var after=catalog.read().get(0);
        assertEquals(100,after.rolloutPercent());assertTrue(after.paused());assertEquals(signed,after.manifest());
    }
    @Test void channelAndTargetingAreAppliedAndImmutableOnRetry() throws Exception {
        uploadAll();var r=release(1,10).put("channel","staging").put("minAppVersion",2).put("maxAppVersion",5);
        store.register(r);var stored=catalog.read().get(0);
        assertEquals("staging",stored.channel());assertEquals(10,stored.rolloutPercent());assertEquals(2L,stored.minAppVersion());
        r.put("channel","production");assertThrows(PublicationStore.Conflict.class,()->store.register(r));
    }
    @Test void authRequiredForEveryMutationIncludingDisabledConfiguration() throws Exception {
        api.perform(put("/publish/artifacts/"+hash).content(bundle)).andExpect(status().isUnauthorized());
        api.perform(post("/publish/releases").content("{}")).andExpect(status().isUnauthorized());
        api.perform(put("/publish/releases/"+hash+"/rollout").header("Authorization","Bearer wrong").content("{}"))
                .andExpect(status().isUnauthorized());
        var disabled=MockMvcBuilders.standaloneSetup(new PublicationController(store,mapper,"")).build();
        disabled.perform(post("/publish/releases").header("Authorization","Bearer "+token).content("{}"))
                .andExpect(status().isUnauthorized());
    }
    @Test void malformedAndOversizedMetadataAreRejected() throws Exception {
        api.perform(post("/publish/releases").header("Authorization","Bearer "+token).content(" ".repeat(65537))).andExpect(status().isBadRequest());
        api.perform(post("/publish/releases").header("Authorization","Bearer "+token).content("{}")).andExpect(status().isBadRequest());
        assertTrue(catalog.read().isEmpty());
    }
    @Test void corruptExistingArtifactCannotBeRegisteredOrOverwritten() throws Exception {
        uploadAll();Files.write(dir.resolve("objects").resolve(hash),image);
        assertThrows(IllegalArgumentException.class,()->store.register(release(1,100)));
        assertThrows(PublicationStore.Conflict.class,()->store.upload(hash,new ByteArrayInputStream(bundle)));
    }
    @Test void dependencyHeaderMustEqualManifestImages() throws Exception {
        bundle="// dootah-images:\nmissing dependency".getBytes();hash=PublicationStore.digest(bundle);uploadAll();
        assertThrows(IllegalArgumentException.class,()->store.register(release(1,100)));
    }
    @Test void restartAndConcurrentRetriesHaveOneIdentity() throws Exception {
        uploadAll();var r=release(1,100);var executor=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {var futures=new ArrayList<java.util.concurrent.Future<?>>();for(int i=0;i<10;i++)futures.add(executor.submit(()->{try{return store.register(r);}catch(Exception e){throw new RuntimeException(e);}}));for(var f:futures)f.get();}
        finally {executor.shutdown();}
        var restarted=new PublicationStore(catalog,mapper,dir.resolve("objects").toString(),origin);
        assertEquals("existing",restarted.register(r).get("status"));assertEquals(1,catalog.read().size());
    }
}
