package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.google.gson.GsonBuilder;
import com.miaokatze.miaokatzecore.api.testlab.LabCase;

public class TestLabCatalogTest {

    @Test
    @SuppressWarnings("unchecked")
    public void exportsDeclaredCatalogWithoutStartingAWorldOrScenario() throws Exception {
        Field registry = TestWorldManager.class.getDeclaredField("SCENARIOS");
        registry.setAccessible(true);
        Map<String, TestScenario> scenarios = (Map<String, TestScenario>) registry.get(null);
        Map<String, TestScenario> previous = new LinkedHashMap<>(scenarios);
        try {
            scenarios.clear();
            ScenarioRegistry.register();
            List<LabCase> cases = new TestLabBootstrap().cases();
            assertEquals(30, scenarios.size());
            assertEquals(42, cases.size());
            assertEquals(
                19,
                cases.stream()
                    .filter(LabCase::defaultSuite)
                    .count());
            List<Map<String, Object>> entries = new ArrayList<>();
            for (LabCase entry : cases) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", entry.id());
                item.put("title", entry.title());
                item.put("lane", entry.lane());
                item.put("tags", entry.tags());
                item.put("defaultSuite", entry.defaultSuite());
                item.put("requiresRealActor", entry.requiresRealActor());
                entries.add(item);
            }
            String target = System.getProperty("gtswn.testlab.catalog");
            if (target != null) {
                Map<String, Object> document = new LinkedHashMap<>();
                document.put("schemaVersion", 1);
                document.put("module", "gtswn");
                document.put(
                    "evidence",
                    "declared catalog only; runtime list is authoritative; no scenarios were executed");
                document.put("cases", entries);
                Path output = Paths.get(target)
                    .toAbsolutePath()
                    .normalize();
                Files.createDirectories(output.getParent());
                Files.write(
                    output,
                    new GsonBuilder().setPrettyPrinting()
                        .create()
                        .toJson(document)
                        .getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            scenarios.clear();
            scenarios.putAll(previous);
        }
    }
}
