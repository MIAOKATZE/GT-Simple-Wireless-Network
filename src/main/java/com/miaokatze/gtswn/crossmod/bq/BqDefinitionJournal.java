package com.miaokatze.gtswn.crossmod.bq;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.miaokatzecore.api.io.AtomicFilePublisher;

/** Write-ahead intent, never a substitute for observing BetterQuesting's actual database bytes. */
final class BqDefinitionJournal {

    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final Path file;
    private final JsonObject state;
    private final Set<String> apply = new HashSet<>();

    private BqDefinitionJournal(Path file, JsonObject state) {
        this.file = file;
        this.state = state;
    }

    static BqDefinitionJournal prepare(Path file, String version, boolean upgrade, JsonObject current,
        JsonObject desired) throws IOException {
        JsonObject prior = read(file);
        boolean pending = prior != null && "PENDING".equals(
            prior.get("status")
                .getAsString());
        if (!upgrade && !pending) return null;
        JsonObject state = new JsonObject();
        state.addProperty("schema", 1);
        state.addProperty("version", version);
        state.addProperty("status", "PENDING");
        JsonObject before = new JsonObject(), after = new JsonObject(), conflicts = new JsonObject();
        BqDefinitionJournal result = new BqDefinitionJournal(file, state);
        for (Map.Entry<String, JsonElement> entry : desired.entrySet()) {
            String key = entry.getKey();
            JsonElement live = value(current, key), target = entry.getValue();
            if (pending) {
                JsonElement old = value(prior.getAsJsonObject("before"), key);
                JsonElement previousTarget = value(prior.getAsJsonObject("after"), key);
                if (prior.has("conflicts") && prior.getAsJsonObject("conflicts")
                    .has(key) || !live.equals(old) && !live.equals(previousTarget)) {
                    // An editor/default reload changed this definition after intent was recorded.
                    // Accept its value explicitly; never overwrite it on every subsequent startup.
                    conflicts.add(key, live);
                    target = live;
                }
            }
            before.add(key, live);
            after.add(key, target);
            if (!live.equals(target)) result.apply.add(key);
        }
        state.add("before", before);
        state.add("after", after);
        state.add("conflicts", conflicts);
        result.persist(); // Failure aborts injection BEFORE any live definition mutation.
        return result;
    }

    boolean shouldApply(String key) {
        return apply.contains(key);
    }

    int conflicts() {
        return state.getAsJsonObject("conflicts")
            .entrySet()
            .size();
    }

    /** Native serialization after changes is the authority, including surviving editor modifications. */
    void expect(JsonObject live) throws IOException {
        JsonObject expected = state.getAsJsonObject("after");
        for (Map.Entry<String, JsonElement> entry : expected.entrySet()) entry.setValue(value(live, entry.getKey()));
        persist();
    }

    /** Editor/default-load changes during this running session are authoritative too. */
    void acceptLiveChanges(JsonObject live) throws IOException {
        JsonObject expected = state.getAsJsonObject("after");
        for (Map.Entry<String, JsonElement> entry : expected.entrySet()) {
            JsonElement value = value(live, entry.getKey());
            if (!entry.getValue()
                .equals(value)) {
                state.getAsJsonObject("conflicts")
                    .add(entry.getKey(), value);
                entry.setValue(value);
            }
        }
        persist();
    }

    boolean matches(JsonObject disk) {
        for (Map.Entry<String, JsonElement> entry : state.getAsJsonObject("after")
            .entrySet()) {
            if (!entry.getValue()
                .equals(value(disk, entry.getKey()))) return false;
        }
        return true;
    }

    void committed() throws IOException {
        state.addProperty("status", conflicts() == 0 ? "COMMITTED" : "COMMITTED_WITH_CONFLICTS");
        persist();
    }

    String version() {
        return state.get("version")
            .getAsString();
    }

    private void persist() throws IOException {
        byte[] bytes = state.toString()
            .getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("BQ definition intent exceeds byte limit");
        Files.createDirectories(
            file.toAbsolutePath()
                .getParent());
        AtomicFilePublisher.publishAtomic(file, bytes);
    }

    private static JsonObject read(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        if (Files.size(file) > MAX_BYTES) throw new IOException("Oversized BQ definition journal");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject state = new JsonParser().parse(reader)
                .getAsJsonObject();
            if (state.get("schema")
                .getAsInt() != 1 || !state.has("before")
                || !state.has("after")
                || !state.has("status")
                || !state.has("version")) throw new IOException("Invalid BQ definition journal");
            return state;
        } catch (RuntimeException malformed) {
            throw new IOException("Malformed BQ definition journal; retain for repair", malformed);
        }
    }

    private static JsonElement value(JsonObject values, String key) {
        JsonElement value = values.get(key);
        return value == null ? JsonNull.INSTANCE : value;
    }
}
