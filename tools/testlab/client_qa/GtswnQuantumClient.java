package miaotest.host;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static miaotest.host.ClientAccess.*;

/** GTSWN owns quantum fixture phases, actor receipts and business assertions. */
public final class GtswnQuantumClient {
    private final HostClientQa host;
    private String quantumControlPhase;
    private long quantumControlBlockedAt;
    private final Set<String> quantumFixtureForwarded = new HashSet<String>();
    public GtswnQuantumClient(HostClientQa host) { this.host = host; }
    public boolean control(String phase) throws Exception {
        if (phase.equals(quantumControlPhase)) return true;
        JsonObject value = new JsonObject(); value.addProperty("hostRunId", host.run); value.addProperty("phase", phase);
        if (host.businessRun != null) value.addProperty("businessRunId", host.businessRun);
        Path temporary = host.out.resolve("quantum-control.json.tmp");
        Files.write(temporary, host.gson.toJson(value).getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporary, host.out.resolve("quantum-control.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            quantumControlPhase = phase; quantumControlBlockedAt = 0; return true;
        } catch (java.nio.file.AccessDeniedException busyReader) {
            long now = System.nanoTime();
            if (quantumControlBlockedAt == 0) quantumControlBlockedAt = now;
            else if (now - quantumControlBlockedAt > 2000000000L) throw busyReader;
            return false;
        }
    }
    public void tick() throws Exception {
        Class<?> driver = host.type("com.miaokatze.gtswn.client.testworld.QuantumSafetyClientCheck");
        if (!host.startedPaged) {
            Path source = host.out.resolve("quantum-server-run.json");
            if (!Files.isRegularFile(source)) return;
            JsonObject value = new JsonParser().parse(new String(Files.readAllBytes(source), StandardCharsets.UTF_8)).getAsJsonObject();
            host.check("quantum.host-console-run-correlation", host.run.equals(value.get("hostRunId").getAsString()), value.toString());
            host.businessRun = UUID.fromString(value.get("businessRunId").getAsString()).toString();
            call(driver, "start", host.businessRun); host.startedPaged = true;
        }
        for (String receiptPhase : new String[] {"build-revoked", "security-ready", "revoked", "rejected"}) {
            if (quantumFixtureForwarded.contains(receiptPhase)) continue;
            Path receiptPath = host.out.resolve("quantum-fixture-" + receiptPhase + ".json");
            if (!Files.isRegularFile(receiptPath)) continue;
            JsonObject receipt = new JsonParser().parse(new String(Files.readAllBytes(receiptPath), StandardCharsets.UTF_8)).getAsJsonObject();
            String actor = String.valueOf(call(field(host.mc, "thePlayer|field_71439_g"), "getUniqueID|func_110124_au"));
            host.check("quantum.fixture-receipt-" + receiptPhase, host.run.equals(receipt.get("hostRunId").getAsString())
                && host.businessRun.equals(receipt.get("businessRunId").getAsString()) && actor.equals(receipt.get("actorUuid").getAsString())
                && receiptPhase.equals(receipt.get("phase").getAsString()), "Coordination receipt is native-server-origin and exact host/business/actual actor bound");
            call(driver, "acceptFixtureReceipt", host.businessRun, actor, receiptPhase);
            host.result.add("fixtureReceipt-" + receiptPhase, receipt);
            quantumFixtureForwarded.add(receiptPhase);
        }
        Map<?, ?> value = (Map<?, ?>) call(driver, "automationSnapshot");
        if (value.get("runId") != null && !host.businessRun.equals(String.valueOf(value.get("runId")))) throw new IllegalStateException("Quantum client returned a stale run");
        String nativePhase = String.valueOf(value.get("phase"));
        if ("WAIT_BUILD_REVOKE".equals(nativePhase) || "WAIT_SECURITY_READY".equals(nativePhase) || "WAIT_REVOKE".equals(nativePhase) || "WAIT_RESTORE".equals(nativePhase)) control(nativePhase);
        if (!"IDLE".equals(nativePhase) || !Boolean.TRUE.equals(value.get("reportWritten"))) return;
        host.check("quantum.real-production-security-client", "PASS".equals(value.get("status")), value.toString());
        host.check("quantum.business-actor-has-no-OP", Boolean.FALSE.equals(value.get("actorLevel2")) && Boolean.FALSE.equals(value.get("actorLevel4")), "Native server permission evidence, not inferred from ops.json");
        host.check("quantum.BUILD-teammate-native-business", Boolean.TRUE.equals(value.get("buildOnlyNativeBinding")) && Boolean.TRUE.equals(value.get("buildOnlyNativeNodeLinked")) && Boolean.TRUE.equals(value.get("buildOnlyFullData")) && Boolean.TRUE.equals(value.get("buildOnlyToggleDenied")) && Boolean.TRUE.equals(value.get("buildOnlyRevokedReadDenied")), "Same non-OP BUILD teammate: real C08 bind/place, native bridge, full Lite business read and revoked same-key denial");
        host.check("quantum.all-native-grants-teammate-business", Boolean.TRUE.equals(value.get("allNativeGrantsFullData")) && Boolean.TRUE.equals(value.get("allNativeGrantsNodeLinked")), "Same nonowner actor with all native permissions: real GUI business read and second real C08 node/native bridge");
        host.check("quantum.native-slot-and-position-restored", Boolean.TRUE.equals(value.get("slotRestoredFromServer")) && Boolean.TRUE.equals(value.get("positionRestoredFromServer")), value.toString());
        host.result.add("nativeProfile", host.gson.toJsonTree(value)); host.finish(null);
    }
}
