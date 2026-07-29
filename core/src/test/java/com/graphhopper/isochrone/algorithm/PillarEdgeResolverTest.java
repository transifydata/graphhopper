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
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.PointList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.graphhopper.json.Statement.If;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the fork's "curved roads" feature: {@link PillarEdgeResolver} expands each
 * {@link ShortestPathTree.IsoLabel} into the edge's full way geometry (tower + pillar nodes),
 * trimming the polyline of overextended edges to the consumed distance.
 */
public class PillarEdgeResolverTest {

    private final BooleanEncodedValue accessEnc = new SimpleBooleanEncodedValue("access", true);
    private final DecimalEncodedValue speedEnc = new DecimalEncodedValueImpl("speed", 7, 1, false);
    private final EncodingManager encodingManager = EncodingManager.start().add(accessEnc).add(speedEnc).build();
    private BaseGraph graph;
    private double dist01, dist12;

    private Weighting createWeighting() {
        CustomModel customModel = new CustomModel();
        customModel.addToPriority(If("!" + accessEnc.getName(), Statement.Op.MULTIPLY, "0"));
        customModel.addToSpeed(If("true", Statement.Op.LIMIT, speedEnc.getName()));
        return CustomModelParser.createWeighting(encodingManager, TurnCostProvider.NO_TURN_COST_PROVIDER, customModel);
    }

    @BeforeEach
    public void setUp() {
        graph = new BaseGraph.Builder(encodingManager).set3D(true).create();
        NodeAccess na = graph.getNodeAccess();

        // two curved edges 0-1 and 1-2, each with two pillar nodes
        na.setNode(0, 52.0000, 13.0000, 0);
        na.setNode(1, 52.0000, 13.0030, 0);
        na.setNode(2, 52.0000, 13.0060, 0);

        PointList pillars01 = new PointList(2, true);
        pillars01.add(52.0005, 13.0010, 0);
        pillars01.add(52.0005, 13.0020, 0);

        PointList pillars12 = new PointList(2, true);
        pillars12.add(52.0005, 13.0040, 0);
        pillars12.add(52.0005, 13.0050, 0);

        dist01 = geometryDistance(na, 0, pillars01, 1);
        dist12 = geometryDistance(na, 1, pillars12, 2);

        EdgeIteratorState edge01 = graph.edge(0, 1).setDistance(dist01).setWayGeometry(pillars01);
        EdgeIteratorState edge12 = graph.edge(1, 2).setDistance(dist12).setWayGeometry(pillars12);
        GHUtility.setSpeed(10, true, true, accessEnc, speedEnc, edge01);
        GHUtility.setSpeed(10, true, true, accessEnc, speedEnc, edge12);
    }

    @AfterEach
    public void tearDown() {
        graph.close();
    }

    private static double geometryDistance(NodeAccess na, int fromNode, PointList pillars, int toNode) {
        PointList full = new PointList(4, true);
        full.add(na.getLat(fromNode), na.getLon(fromNode), na.getEle(fromNode));
        for (int i = 0; i < pillars.size(); i++)
            full.add(pillars.getLat(i), pillars.getLon(i), pillars.getEle(i));
        full.add(na.getLat(toNode), na.getLon(toNode), na.getEle(toNode));
        return DistanceCalcEarth.calcDistance(full, true);
    }

    private List<PillarEdgeResolver.IsoLabel> search(double distanceLimit) {
        List<PillarEdgeResolver.IsoLabel> result = new ArrayList<>();
        PillarEdgeResolver resolver = new PillarEdgeResolver(result::add, graph);
        ShortestPathTree instance = new ShortestPathTree(graph, createWeighting(), false, TraversalMode.NODE_BASED);
        instance.setIncludeOverextendedEdges(true);
        instance.setDistanceLimit(distanceLimit);
        instance.search(0, resolver);
        return result;
    }

    @Test
    public void fullEdgeKeepsCompleteWayGeometry() {
        // limit beyond both edges: both polylines are complete
        List<PillarEdgeResolver.IsoLabel> result = search(dist01 + dist12 + 100);
        assertEquals(2, result.size());

        NodeAccess na = graph.getNodeAccess();
        PillarEdgeResolver.IsoLabel first = result.stream()
                .filter(l -> l.original_label.node == 1).findFirst().orElseThrow();
        // tower + 2 pillars + tower
        assertEquals(4, first.pl.size());
        assertEquals(na.getLat(0), first.pl.getLat(0), 1.e-4);
        assertEquals(na.getLon(0), first.pl.getLon(0), 1.e-4);
        assertEquals(na.getLat(1), first.pl.getLat(3), 1.e-4);
        assertEquals(na.getLon(1), first.pl.getLon(3), 1.e-4);
        assertEquals(dist01, DistanceCalcEarth.calcDistance(first.pl, true), 0.5);
        assertEquals(dist01, first.original_label.consumed_part, 1.e-3);
    }

    @Test
    public void overextendedEdgeGeometryIsTrimmed() {
        // the limit cuts edge 1-2 roughly in half
        double allowed12 = dist12 / 2;
        List<PillarEdgeResolver.IsoLabel> result = search(dist01 + allowed12);
        assertEquals(2, result.size());

        PillarEdgeResolver.IsoLabel crossing = result.stream()
                .filter(l -> l.original_label.node == 2).findFirst().orElseThrow();
        assertEquals(allowed12, crossing.original_label.consumed_part, 1.e-3);

        NodeAccess na = graph.getNodeAccess();
        // the trimmed polyline starts at node 1 and is shorter than the full edge
        assertEquals(na.getLat(1), crossing.pl.getLat(0), 1.e-4);
        assertEquals(na.getLon(1), crossing.pl.getLon(0), 1.e-4);
        assertTrue(crossing.pl.size() < 4, "trimmed geometry should have dropped points, got " + crossing.pl);

        double trimmedLength = DistanceCalcEarth.calcDistance(crossing.pl, true);
        assertTrue(trimmedLength < dist12 - 0.5,
                "trimmed length " + trimmedLength + " should be well below the full edge " + dist12);
        // the trim happens at segment granularity (see PillarEdgeResolver), so allow one
        // segment of slack around the exact consumed distance
        double maxSegment = dist12; // upper bound on any single segment
        assertTrue(trimmedLength <= allowed12 + maxSegment,
                "trimmed length " + trimmedLength + " far beyond allowed " + allowed12);
    }

    @Test
    public void rootLabelAndTinyBudgetsAreSkipped() {
        // limit smaller than dist01: only edge 0-1 is emitted (overextended); the root
        // label (edge == -1) is never emitted
        List<PillarEdgeResolver.IsoLabel> result = search(dist01 / 2);
        assertEquals(1, result.size());
        assertEquals(1, result.get(0).original_label.node);
        assertEquals(dist01 / 2, result.get(0).original_label.consumed_part, 1.e-3);
    }
}
