package dev.dootah.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dootah.contract.SignedUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.*;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UpdateServerTest {
    @TempDir Path temp;
    final ObjectMapper mapper = new ObjectMapper();
    KeyPair key; ObjectNode root; ReleaseCatalog catalog; MockMvc api;
    @BeforeEach void setup() throws Exception {
        key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        root = mapper.createObjectNode();
        root.putObject("publishers").put("example.app", Base64.getEncoder().encodeToString(Arrays.copyOfRange(key.getPublic().getEncoded(),12,44)));
        root.putArray("releases");
        catalog = new ReleaseCatalog(temp.resolve("catalog.json").toString(), mapper);
        api = MockMvcBuilders.standaloneSetup(new UpdateController(catalog)).build();
        save();
    }
    ObjectNode release(int version, String channel, int percent) throws Exception {
        SignedUpdate signed = new SignedUpdate(1,"example.app","9",version,true,"https://localhost/"+version,
                "a".repeat(64),List.of());
        Signature signer=Signature.getInstance("Ed25519");signer.initSign(key.getPrivate());signer.update(signed.signingBytes());
        ObjectNode r=mapper.createObjectNode().put("channel",channel).put("rolloutPercent",percent).put("paused",false);
        r.set("manifest",mapper.readTree(signed.manifestJson(Base64.getEncoder().encodeToString(signer.sign()))));
        root.withArray("releases").add(r);save();return r;
    }
    void save() throws Exception {
        Path pending=temp.resolve("pending.json");mapper.writeValue(pending.toFile(),root);
        Files.move(pending,temp.resolve("catalog.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    UpdateSelector.Request request(String channel,String id) {return new UpdateSelector.Request("example.app","9",1,channel,id);}
    Optional<ReleaseCatalog.Release> select(String channel,String id) throws Exception {return UpdateSelector.select(catalog.read(),request(channel,id));}
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder check(String channel,String id) {
        return get("/updates/check").param("appId","example.app").param("runtimeVersion","9").param("appVersion","1")
                .param("channel",channel).param("installationId",id);
    }
    @Test void productionCannotReceiveStaging() throws Exception {release(73,"staging",100);api.perform(check("production","one")).andExpect(status().isNoContent());}
    @Test void developmentRequiresExplicitProductionSelection() throws Exception {
        release(71,"production",100);api.perform(check("development","one")).andExpect(status().isNoContent());
        api.perform(check("production","one")).andExpect(status().isOk()).andExpect(jsonPath("$.manifest.bundleVersion").value(71));
    }
    @Test void wrongAppIsNeverSelected() throws Exception {
        release(71,"production",100);assertTrue(UpdateSelector.select(catalog.read(),new UpdateSelector.Request("other.app","9",1,"production","one")).isEmpty());
    }
    @Test void wrongRuntimeIsNeverSelected() throws Exception {
        release(71,"production",100);assertTrue(UpdateSelector.select(catalog.read(),new UpdateSelector.Request("example.app","10",1,"production","one")).isEmpty());
    }
    @Test void appVersionBoundsAreInclusiveAndMismatchRejected() throws Exception {
        var r=release(71,"production",100);r.put("minAppVersion",2).put("maxAppVersion",3);save();
        for (long v:new long[]{1,4}) assertTrue(UpdateSelector.select(catalog.read(),new UpdateSelector.Request("example.app","9",v,"production","one")).isEmpty());
        for (long v:new long[]{2,3}) assertTrue(UpdateSelector.select(catalog.read(),new UpdateSelector.Request("example.app","9",v,"production","one")).isPresent());
    }
    @Test void pausedReleaseNotOffered() throws Exception {
        var r=release(72,"production",100);r.put("paused",true);save();api.perform(check("production","new-install")).andExpect(status().isNoContent());
    }
    @Test void zeroSelectsNobody() throws Exception {
        release(72,"production",0);var all=catalog.read();for(int i=0;i<1000;i++)assertTrue(UpdateSelector.select(all,request("production","device-"+i)).isEmpty());
    }
    @Test void hundredSelectsEveryone() throws Exception {
        release(72,"production",100);var all=catalog.read();for(int i=0;i<1000;i++)assertTrue(UpdateSelector.select(all,request("production","device-"+i)).isPresent());
    }
    @Test void tenPercentIsDeterministicAcrossChecksAndFreshCatalogInstances() throws Exception {
        release(72,"production",10);var first=catalog.read();var restarted=new ReleaseCatalog(temp.resolve("catalog.json").toString(),mapper).read();
        for(int i=0;i<1000;i++) {
            var q=request("production","device-"+i);var a=UpdateSelector.select(first,q);
            assertEquals(a,UpdateSelector.select(restarted,q));assertEquals(a,UpdateSelector.select(first,q));
        }
    }
    @Test void differentInstallationsDistributeAcrossAllBuckets() throws Exception {
        release(72,"production",10);var r=catalog.read().get(0);int selected=0;Set<Integer>buckets=new HashSet<>();
        for(int i=0;i<10000;i++){int b=UpdateSelector.bucket("example.app","device-"+i,r.identity());buckets.add(b);if(b<10)selected++;}
        assertEquals(100,buckets.size());assertTrue(selected>850 && selected<1150,"Selected "+selected);
    }
    @Test void percentageChangeDoesNotAlterArtifactSignatureOrCohort() throws Exception {
        var r=release(72,"production",10);var before=catalog.read().get(0);r.put("rolloutPercent",100);save();var after=catalog.read().get(0);
        assertEquals(before.identity(),after.identity());assertEquals(before.manifest(),after.manifest());
        for(int i=0;i<100;i++)assertEquals(UpdateSelector.bucket("example.app","d"+i,before.identity()),UpdateSelector.bucket("example.app","d"+i,after.identity()));
    }
    @Test void malformedRequestsAreBadRequests() throws Exception {
        for(var q:List.of(get("/updates/check"),check("unknown","one"),check("production","bad id"),
                check("production",""),get("/updates/check").param("appVersion","NaN")))api.perform(q).andExpect(status().isBadRequest());
    }
    @Test void highestEligibleReleaseSelectedAndExcludedReleaseDoesNotHideBaseline() throws Exception {
        release(71,"production",100);release(72,"production",0);release(73,"staging",100);
        assertEquals(71,select("production","one").orElseThrow().version());
    }
    @Test void responsePreservesSignedManifestAndCannotBeCached() throws Exception {
        var r=release(71,"production",100);api.perform(check("production","one")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.manifest.signature").value(r.path("manifest").path("signature").textValue()));
    }
    @Test void forgedManifestAndUnknownPublisherFailClosed() throws Exception {
        var r=release(71,"production",100);((ObjectNode)r.get("manifest")).put("bundleVersion",90);save();
        api.perform(check("production","one")).andExpect(status().isServiceUnavailable());
    }
    @Test void invalidPercentageBoundsOrChannelRejectWholeCatalog() throws Exception {
        var r=release(71,"production",100);r.put("rolloutPercent",101);save();assertThrows(IllegalArgumentException.class,catalog::read);
        r.put("rolloutPercent",10).put("minAppVersion",3).put("maxAppVersion",2);save();assertThrows(IllegalArgumentException.class,catalog::read);
        r.remove("minAppVersion");r.remove("maxAppVersion");r.put("channel","other");save();assertThrows(IllegalArgumentException.class,catalog::read);
    }
    @Test void duplicateArtifactCannotBelongToTwoChannels() throws Exception {
        var r=release(71,"production",100);root.withArray("releases").add(r.deepCopy().put("channel","staging"));save();
        assertThrows(IllegalArgumentException.class,catalog::read);
    }
    @Test void interruptedCatalogReplacementOffersNothingInsteadOfWrongContent() throws Exception {
        release(71,"production",100);Files.writeString(temp.resolve("catalog.json"),"{");
        api.perform(check("production","one")).andExpect(status().isServiceUnavailable());
    }
}
