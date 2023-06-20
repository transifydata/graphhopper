package com.graphhopper.isochrone.algorithm;

import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.DefaultSnapFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.FastestWeighting;
import com.graphhopper.storage.*;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PillarEdgeResolverTest {
    private static final String DIR = "../core/files";
    private static final String ANDORRA = DIR + "/andorra.osm.gz";
    private GraphHopper hopper;


    private static final String GH_LOCATION = "target/pillar-edge-resolver-test";


    @BeforeEach
    public void setup() {
        Helper.removeDir(new File(GH_LOCATION));

        GraphHopper hopper = new GraphHopper();
        hopper.setOSMFile(ANDORRA);
        hopper.setGraphHopperLocation(GH_LOCATION);
        hopper.setStoreOnFlush(false);

        hopper.setProfiles(new Profile("car").setVehicle("car").setWeighting("fastest").setTurnCosts(false));

        hopper.importOrLoad();
        this.hopper = hopper;
    }

    @AfterEach
    public void tearDown() {
        hopper.close();
    }



    // Checks that two IsoLabels are similar regarding node and edge
    boolean checkLabelSimilar(ShortestPathTree.IsoLabel a, ShortestPathTree.IsoLabel b) {
        return a.node == b.node && a.edge == b.edge;
    }
//    @Test
//    public void testPillarEdgesReturnMoreNodes() {
//        Graph graph = hopper.getBaseGraph();
//        NodeAccess na = graph.getNodeAccess();
//
//        EncodingManager encodingManager = hopper.getEncodingManager();
//        BooleanEncodedValue accessEnc = encodingManager.getBooleanEncodedValue(VehicleAccess.key("car"));
//        DecimalEncodedValue speedEnc = encodingManager.getDecimalEncodedValue(VehicleSpeed.key("car"));
//
//        // snap some GPS coordinates to the routing graph and build a query graph
//        FastestWeighting weighting = new FastestWeighting(accessEnc, speedEnc);
//        Snap snap = hopper.getLocationIndex().findClosest( 42.55550660241878, 1.5331202857958102, new DefaultSnapFilter(weighting, encodingManager.getBooleanEncodedValue(Subnetwork.key("car"))));
//
//        List<PillarEdgeResolver.IsoLabel> result = new ArrayList<>();
//        List<ShortestPathTree.IsoLabel> result_short = new ArrayList<>();
//        PillarEdgeResolver r = new PillarEdgeResolver(result::add, graph);
//        ShortestPathTree instance = new ShortestPathTree(graph, new FastestWeighting(accessEnc, speedEnc), false, TraversalMode.NODE_BASED);
//
//        instance.setIncludeOverextendedEdges(true);
//        instance.setDistanceLimit(1000);
//
//        instance.search(snap.getClosestNode(), r.andThen(result_short::add));
//
//        assertEquals(result.size(), 332);
//        assertEquals(result_short.size(), 119);
//
//        int pillarIndex = 0;
//        for (int towerIndex = 0; towerIndex < result_short.size(); towerIndex++) {
//            PillarEdgeResolver.IsoLabel starting_label = result.get(pillarIndex);
//            assert checkLabelSimilar(starting_label.original_label, result_short.get(towerIndex));
//
//            // Check that the starting location of the multi-edge path is the same
//            if (starting_label.prev_node != null) {
//                assertEquals(starting_label.prev_node.getLat(), na.getLat(result_short.get(towerIndex).parent.node), 0.01);
//                assertEquals(starting_label.prev_node.getLon(), na.getLon(result_short.get(towerIndex).parent.node), 0.01);
//            }
//
//
//            while (pillarIndex < result.size() - 1) {
//                PillarEdgeResolver.IsoLabel label = result.get(++pillarIndex);
//                ShortestPathTree.IsoLabel b = result_short.get(towerIndex);
//
//
//                if(!checkLabelSimilar(label.original_label, b)) {
//                    break;
//                }
//                assert label.original_label.distance <= b.distance;
//            }
//
//            // Check that the ending location of the multi-edge path is the same
//            assertEquals(result.get(pillarIndex - 1).node.getLat(), na.getLat(result_short.get(towerIndex).node), 0.01);
//            assertEquals(result.get(pillarIndex - 1).node.getLon(), na.getLon(result_short.get(towerIndex).node), 0.01);
//
//        }
//
//    }

}
