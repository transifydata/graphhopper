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
import com.graphhopper.config.Profile;
import com.graphhopper.util.Helper;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import java.io.File;
import java.util.Arrays;
import java.util.List;

import static com.graphhopper.application.util.TestUtils.clientTarget;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(DropwizardExtensionsSupport.class)
public class BatchSPTResourceTest {
    private static final String DIR = "./target/spt-gh/";
    private static final DropwizardAppExtension<GraphHopperServerConfiguration> app = new DropwizardAppExtension<>(GraphHopperApplication.class, createConfig());

    private static GraphHopperServerConfiguration createConfig() {
        GraphHopperServerTestConfiguration config = new GraphHopperServerTestConfiguration();
        config.getGraphHopperConfiguration().
                putObject("graph.vehicles", "car|turn_costs=true").
                putObject("graph.encoded_values", "max_speed,road_class").
                putObject("datareader.file", "../core/files/andorra.osm.pbf").
                putObject("import.osm.ignored_highways", "").
                putObject("graph.location", DIR).
                setProfiles(Arrays.asList(
                        new Profile("car_without_turncosts").setVehicle("car").setWeighting("fastest"),
                        new Profile("car_with_turncosts").setVehicle("car").setWeighting("fastest").setTurnCosts(true)
                ));
        return config;
    }

    @BeforeAll
    @AfterAll
    public static void cleanUp() {
        Helper.removeDir(new File(DIR));
    }

    @Test
    public void requestSPT() {
        final String jsonStr = "{\"points\": [[42.531073,1.573792], [42.631073,1.573792], [42.475856128701196, 1.489418655878341]]}";
        Response rsp = clientTarget(app, "/batch-spt?profile=car_without_turncosts&distance_limit=2000").request().buildPost(Entity.json(jsonStr)).invoke();
        JsonNode rspCsvString = rsp.readEntity(JsonNode.class);

        System.out.println(rspCsvString);
//        System.out.print("[");
//        boolean first = true;
//        for (JsonNode n : rspCsvString) {
//            double[] elem = new double[]{n.get(0).asDouble(), n.get(1).asDouble(), n.get(2).asDouble(), n.get(3).asDouble()};
//
//            if (first) {
//                first = false;
//            } else {
//                System.out.print(",");
//            }
//            System.out.printf("[[%f, %f], [%f, %f]]", elem[1], elem[0], elem[3], elem[2]);
//        }
////        System.out.println(rspCsvString);
//        System.out.print("]\n");
    }


}
