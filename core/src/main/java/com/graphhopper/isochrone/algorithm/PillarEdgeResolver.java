package com.graphhopper.isochrone.algorithm;

import com.graphhopper.storage.Graph;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.GHPoint;
import com.graphhopper.util.shapes.GHPoint3D;

import java.util.function.Consumer;

public class PillarEdgeResolver implements Consumer<ShortestPathTree.IsoLabel> {
    Consumer<IsoLabel> consumer;
    Graph graph;

    public static class IsoLabel {
        public ShortestPathTree.IsoLabel original_label;
        public GHPoint3D node, prev_node;

        IsoLabel(ShortestPathTree.IsoLabel original_label, GHPoint3D node, GHPoint3D prev_node) {
            this.original_label = original_label;
            this.node = node;
            this.prev_node = prev_node;
        }

        @Override
        public String toString() {
            return "PER.IsoLabel{" + original_label + ", " + node + ", " + prev_node + "} ";
        }
    }

    public PillarEdgeResolver(final Consumer<IsoLabel> consumer, Graph g) {
        this.consumer = consumer;
        this.graph = g;
    }

    void assertEquals(double a, double b) {
        assert Math.abs(a - b) < 0.001;
    }
    @Override
    public void accept(ShortestPathTree.IsoLabel label) {
        if (label.edge == -1 || label.edge >= graph.getEdges()) return;
        EdgeIteratorState edge = graph.getEdgeIteratorState(label.edge, label.node);

        double allowed_distance = label.consumed_part;
        double consumed_distance = 0;

        PointList geom = edge.fetchWayGeometry(FetchMode.ALL);
        NodeAccess na = graph.getNodeAccess();

        GHPoint3D prev_element = null;
        ShortestPathTree.IsoLabel parent_label = label;


        assertEquals(geom.getLat(0), na.getLat(label.parent.node));
        assertEquals(geom.getLon(0), na.getLon(label.parent.node));
        assertEquals(geom.getLat(geom.size() - 1), na.getLat(label.node));
        assertEquals(geom.getLon(geom.size() - 1), na.getLon(label.node));


        for (GHPoint3D element : geom) {
            if (Math.abs(consumed_distance - allowed_distance) < 0.01) break;

            if (prev_element != null) {
                double this_distance = DistanceCalcEarth.DIST_EARTH.calcDist3D(element.lat, element.lon, element.ele, prev_element.lat, prev_element.lon, prev_element.ele);

                if (consumed_distance + this_distance > allowed_distance) {
                    // Consuming this edge will bring us over the limit
                    // Have to interpolate along element/prev_element by (allowed_distance - consumed_distance) amount units
                    double additional_distance = allowed_distance - consumed_distance;
                    double fraction_distance = additional_distance / this_distance;

                    GHPoint intermediate_2d = DistanceCalcEarth.DIST_EARTH.intermediatePoint(fraction_distance, prev_element.lat, prev_element.lon, element.lat, element.lon);

                    // Copy elevation to 3d point
                    GHPoint3D intermediate = new GHPoint3D(intermediate_2d.lat, intermediate_2d.lon, element.ele);
                    element = intermediate;
                    consumed_distance += additional_distance;
                } else {
                    consumed_distance += this_distance;
                }


                ShortestPathTree.IsoLabel spt_isolabel = new ShortestPathTree.IsoLabel(label.node, label.edge, label.weight, label.time, label.parent.distance + consumed_distance, parent_label);
                spt_isolabel.consumed_part = this_distance;
                IsoLabel this_label = new IsoLabel(spt_isolabel, element, prev_element);
                parent_label = spt_isolabel;

                this.consumer.accept(this_label);
            }

            prev_element = element;
        }

        assert Math.abs(allowed_distance - consumed_distance) < 0.5: String.valueOf(allowed_distance) + " " + String.valueOf(consumed_distance);
    }


}
