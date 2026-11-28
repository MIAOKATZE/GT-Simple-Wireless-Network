package miaotest.host;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/** Exercises the delegated file protocol only; no Minecraft instance or connection. */
public final class GtswnQuantumClientContract {
    private static void require(boolean value) { if (!value) throw new AssertionError("Quantum control contract"); }
    public static void main(String[] args) throws Exception {
        HostClientQa host = new HostClientQa();
        host.run = UUID.randomUUID().toString();
        host.businessRun = UUID.randomUUID().toString();
        host.out = Paths.get(args[0]);
        host.quantumDriver = new GtswnQuantumClient(host);
        require(host.quantumControl("WAIT_START"));
        Path path = host.out.resolve("quantum-control.json");
        byte[] original = Files.readAllBytes(path);
        require(host.quantumControl("WAIT_START"));
        require(java.util.Arrays.equals(original, Files.readAllBytes(path)));
        require(host.quantumControl("WAIT_REVOKE"));
        JsonObject row = new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
        require(host.run.equals(row.get("hostRunId").getAsString()));
        require(host.businessRun.equals(row.get("businessRunId").getAsString()));
        require("WAIT_REVOKE".equals(row.get("phase").getAsString()));
        System.out.println("{\"passed\":true,\"checks\":7,\"gameStarted\":false}");
    }
}
