package com.graphhopper.isochrone.algorithm;

import com.graphhopper.json.Statement;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValueImpl;
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.TurnCostProvider;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.routing.weighting.custom.CustomModelParser;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.Graph;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.GHUtility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.graphhopper.json.Statement.If;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the fork's "overextended edges" feature of {@link ShortestPathTree}:
 * when a distance limit cuts through an edge, the label for that edge is still emitted
 * (once, at discovery time) with {@code consumed_part} set to the remaining distance
 * budget instead of the full edge length. Labels fully within the limit carry
 * {@code consumed_part} equal to their full edge distance.
 */
public class ShortestPathTreeOverextendedTest {

    private final BooleanEncodedValue accessEnc = new SimpleBooleanEncodedValue("access", true);
    private final DecimalEncodedValue speedEnc = new DecimalEncodedValueImpl("speed", 7, 1, false);
    private final EncodingManager encodingManager = EncodingManager.start().add(accessEnc).add(speedEnc).build();
    private BaseGraph graph;

    private Weighting createWeighting() {
        CustomModel customModel = new CustomModel();
        customModel.addToPriority(If("!" + accessEnc.getName(), Statement.Op.MULTIPLY, "0"));
        customModel.addToSpeed(If("true", Statement.Op.LIMIT, speedEnc.getName()));
        return CustomModelParser.createWeighting(encodingManager, TurnCostProvider.NO_TURN_COST_PROVIDER, customModel);
    }

    @BeforeEach
    public void setUp() {
        graph = new BaseGraph.Builder(encodingManager).create();
    }

    @AfterEach
    public void tearDown() {
        graph.close();
    }

    // 0 --100m-- 1 --100m-- 2 --100m-- 3
    //            |
    //           40m
    //            |
    //            4
    private void createLineGraph() {
        GHUtility.setSpeed(10, true, true, accessEnc, speedEnc, ((Graph) graph).edge(0, 1).setDistance(100));
        GHUtility.setSpeed(10, true, true, accessEnc, speedEnc, ((Graph) graph).edge(1, 2).setDistance(100));
        GHUtility.setSpeed(10, true, true, accessEnc, speedEnc, ((Graph) graph).edge(2, 3).setDistance(100));
        GHUtility.setSpeed(10, true, true, accessEnc, speedEnc, ((Graph) graph).edge(1, 4).setDistance(40));
    }

    private List<ShortestPathTree.IsoLabel> search(double distanceLimit, boolean includeOverextended) {
        ShortestPathTree instance = new ShortestPathTree(graph, createWeighting(), false, TraversalMode.NODE_BASED);
        instance.setIncludeOverextendedEdges(includeOverextended);
        instance.setDistanceLimit(distanceLimit);
        List<ShortestPathTree.IsoLabel> result = new ArrayList<>();
        instance.search(0, result::add);
        return result;
    }

    @Test
    public void withoutOverextendedEdgesOnlyWithinLimit() {
        createLineGraph();
        List<ShortestPathTree.IsoLabel> labels = search(150, false);
        // root (node 0), node 1 (100m), node 4 (140m); node 2 is at 200m > 150m
        assertEquals(List.of(0, 1, 4), labels.stream().map(l -> l.node).sorted().collect(Collectors.toList()));
        for (ShortestPathTree.IsoLabel l : labels)
            assertTrue(l.distance <= 150, "unexpected beyond-limit label " + l);
    }

    @Test
    public void overextendedEdgeEmittedWithPartialConsumption() {
        createLineGraph();
        List<ShortestPathTree.IsoLabel> labels = search(150, true);
        Map<Integer, ShortestPathTree.IsoLabel> byNode = labels.stream()
                .collect(Collectors.toMap(l -> l.node, Function.identity()));

        // within-limit labels as before, plus the crossing edge 1->2
        assertEquals(List.of(0, 1, 2, 4), byNode.keySet().stream().sorted().collect(Collectors.toList()));

        // full-edge labels report the full edge distance as consumed
        assertEquals(100, byNode.get(1).consumed_part, 1.e-6);
        assertEquals(40, byNode.get(4).consumed_part, 1.e-6);

        // the crossing label is beyond the limit and only partially consumed:
        // limit (150) - distance at parent node 1 (100) = 50
        ShortestPathTree.IsoLabel crossing = byNode.get(2);
        assertEquals(200, crossing.distance, 1.e-6);
        assertEquals(1, crossing.parent.node);
        assertEquals(50, crossing.consumed_part, 1.e-6);

        // edge 2->3 starts beyond the limit entirely and must not be emitted
        assertFalse(byNode.containsKey(3));
    }

    @Test
    public void exactlyAtLimitLabelIsNotEmitted() {
        // Upstream (since 10.0) terminates once the smallest remaining explore value
        // reaches the limit, so a label EXACTLY at the limit is not emitted anymore
        // (the pre-merge fork emitted it). With continuous distances this is a
        // measure-zero edge case, but we pin the behavior down here.
        createLineGraph();
        List<ShortestPathTree.IsoLabel> labels = search(100, true);
        assertEquals(List.of(0), labels.stream().map(l -> l.node).sorted().collect(Collectors.toList()));

        // just above the limit the node is polled and both crossing edges (1->2 and 1->4)
        // get the tiny remaining budget
        List<ShortestPathTree.IsoLabel> labelsAbove = search(100.5, true);
        assertEquals(List.of(0, 1, 2, 4), labelsAbove.stream().map(l -> l.node).sorted().collect(Collectors.toList()));
        for (int crossingNode : new int[]{2, 4}) {
            ShortestPathTree.IsoLabel crossing = labelsAbove.stream().filter(l -> l.node == crossingNode).findFirst().orElseThrow();
            assertEquals(0.5, crossing.consumed_part, 1.e-6);
        }
    }

    @Test
    public void weightAndDistanceOrderDivergence() {
        // A fast-but-long edge is polled before a slow-but-short one. The search must not
        // terminate early (upstream 10.0 fix) and the overextended bookkeeping must use
        // the distance metric, not the weighting.
        GHUtility.setSpeed(60, true, true, accessEnc, speedEnc, ((Graph) graph).edge(0, 1).setDistance(1000));
        GHUtility.setSpeed(5, true, true, accessEnc, speedEnc, ((Graph) graph).edge(0, 2).setDistance(100));

        List<ShortestPathTree.IsoLabel> labels = search(500, true);
        Map<Integer, ShortestPathTree.IsoLabel> byNode = labels.stream()
                .collect(Collectors.toMap(l -> l.node, Function.identity()));

        // the slow short edge is fully inside the limit and must be found
        assertTrue(byNode.containsKey(2));
        assertEquals(100, byNode.get(2).consumed_part, 1.e-6);
        // the fast long edge crosses the limit at 500m
        assertTrue(byNode.containsKey(1));
        assertEquals(500, byNode.get(1).consumed_part, 1.e-6);
    }

    @Test
    public void overextendedSupersetOfNormalSearch() {
        createLineGraph();
        List<ShortestPathTree.IsoLabel> without = search(150, false);
        List<ShortestPathTree.IsoLabel> with = search(150, true);
        List<Integer> withoutNodes = without.stream().map(l -> l.node).sorted().collect(Collectors.toList());
        List<Integer> withNodes = with.stream().map(l -> l.node).sorted().collect(Collectors.toList());
        assertTrue(withNodes.containsAll(withoutNodes));
        assertTrue(withNodes.size() > withoutNodes.size());
    }
}
