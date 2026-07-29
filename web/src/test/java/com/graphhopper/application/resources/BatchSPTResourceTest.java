/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.graphhopper.application.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.graphhopper.application.GraphHopperApplication;
import com.graphhopper.application.GraphHopperServerConfiguration;
import com.graphhopper.application.util.GraphHopperServerTestConfiguration;
import com.graphhopper.routing.TestProfiles;
import com.graphhopper.util.Helper;
import com.graphhopper.util.TurnCostsConfig;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import java.io.File;
import java.util.Arrays;

import static com.graphhopper.application.util.TestUtils.clientTarget;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the fork's /batch-spt endpoint: several shortest-path trees per request,
 * optional per-point distance limits, overextended (partially consumed) boundary edges
 * and the buffered-polygon (isochrone) output.
 */
@ExtendWith(DropwizardExtensionsSupport.class)
public class BatchSPTResourceTest {
    private static final String DIR = "./target/batch-spt-gh/";
    private static final DropwizardAppExtension<GraphHopperServerConfiguration> app = new DropwizardAppExtension<>(GraphHopperApplication.class, createConfig());

    // central Andorra la Vella, well inside the test map
    private static final String POINT = "42.531073,1.573792";

    private static GraphHopperServerConfiguration createConfig() {
        GraphHopperServerTestConfiguration config = new GraphHopperServerTestConfiguration();
        config.getGraphHopperConfiguration().
                putObject("graph.encoded_values", "max_speed, road_class, car_access, car_average_speed").
                putObject("datareader.file", "../core/files/andorra.osm.pbf").
                putObject("import.osm.ignored_highways", "").
                putObject("graph.location", DIR).
                setProfiles(Arrays.asList(
                        TestProfiles.accessAndSpeed("car_without_turncosts", "car"),
                        TestProfiles.accessAndSpeed("car_with_turncosts", "car").setTurnCostsConfig(TurnCostsConfig.car())
                ));
        return config;
    }

    @BeforeAll
    @AfterAll
    public static void cleanUp() {
        Helper.removeDir(new File(DIR));
    }

    private static JsonNode post(String query, String jsonBody, int expectedStatus) {
        Response rsp = clientTarget(app, "/batch-spt" + query).request().buildPost(Entity.json(jsonBody)).invoke();
        assertEquals(expectedStatus, rsp.getStatus());
        return rsp.readEntity(JsonNode.class);
    }

    private static int countCoordinates(JsonNode multiLineString) {
        assertEquals("MultiLineString", multiLineString.get("type").asText());
        int count = 0;
        for (JsonNode line : multiLineString.get("coordinates"))
            count += line.size();
        return count;
    }

    @Test
    public void batchWithGlobalDistanceLimit() {
        JsonNode geo = post("?profile=car_without_turncosts&distance_limit=1000",
                "{\"points\": [[" + POINT + "]]}", 200);
        assertTrue(countCoordinates(geo) > 0);
    }

    @Test
    public void multiplePointsProduceMoreGeometry() {
        JsonNode one = post("?profile=car_without_turncosts&distance_limit=1000",
                "{\"points\": [[" + POINT + "]]}", 200);
        JsonNode two = post("?profile=car_without_turncosts&distance_limit=1000",
                "{\"points\": [[" + POINT + "], [42.507145,1.521931]]}", 200);
        assertTrue(countCoordinates(two) > countCoordinates(one),
                "a second origin should add geometry");
    }

    @Test
    public void perPointLimitOverridesGlobal() {
        // the same origin, once with a small per-point limit despite a large global limit
        // and once with a large per-point limit despite a small global limit
        JsonNode small = post("?profile=car_without_turncosts&distance_limit=5000",
                "{\"points\": [[" + POINT + ",300]]}", 200);
        JsonNode large = post("?profile=car_without_turncosts&distance_limit=300",
                "{\"points\": [[" + POINT + ",5000]]}", 200);
        assertTrue(countCoordinates(large) > countCoordinates(small),
                "per-point limit must take precedence over the global query parameter");
    }

    @Test
    public void differentLimitsPerPointInOneBatch() {
        JsonNode smallOnly = post("?profile=car_without_turncosts&distance_limit=-1",
                "{\"points\": [[" + POINT + ",300]]}", 200);
        JsonNode mixed = post("?profile=car_without_turncosts&distance_limit=-1",
                "{\"points\": [[" + POINT + ",300], [" + POINT + ",3000]]}", 200);
        assertTrue(countCoordinates(mixed) > countCoordinates(smallOnly));
    }

    @Test
    public void overextendedEdgesAddBoundaryGeometry() {
        JsonNode with = post("?profile=car_without_turncosts&distance_limit=1000&include_overextended_edges=true",
                "{\"points\": [[" + POINT + "]]}", 200);
        JsonNode without = post("?profile=car_without_turncosts&distance_limit=1000&include_overextended_edges=false",
                "{\"points\": [[" + POINT + "]]}", 200);
        assertTrue(countCoordinates(with) >= countCoordinates(without));
        assertTrue(countCoordinates(without) > 0);
    }

    @Test
    public void bufferedPolygonOutput() {
        // exercises PillarEdgeResolver -> EdgeBuffering (parallel windowed buffering + union)
        JsonNode geo = post("?profile=car_without_turncosts&distance_limit=1500&calculate_buffer_distance=100",
                "{\"points\": [[" + POINT + "]]}", 200);
        assertTrue(geo.get("type").asText().contains("Polygon"),
                "expected (Multi)Polygon, got " + geo.get("type"));
        assertTrue(geo.get("coordinates").size() > 0);
    }

    @Test
    public void turnCostsProfileWorks() {
        JsonNode geo = post("?profile=car_with_turncosts&distance_limit=1000",
                "{\"points\": [[" + POINT + "]]}", 200);
        assertTrue(countCoordinates(geo) > 0);
    }

    @Test
    public void missingDistanceLimitFails() {
        Response rsp = clientTarget(app, "/batch-spt?profile=car_without_turncosts")
                .request().buildPost(Entity.json("{\"points\": [[" + POINT + "]]}")).invoke();
        assertTrue(rsp.getStatus() >= 400, "missing distance_limit must be rejected, got " + rsp.getStatus());
    }
}
