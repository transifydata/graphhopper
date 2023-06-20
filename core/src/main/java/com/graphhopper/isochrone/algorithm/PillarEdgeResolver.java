package com.graphhopper.isochrone.algorithm;

import com.graphhopper.storage.Graph;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.GHPoint;
import com.graphhopper.util.shapes.GHPoint3D;

import java.util.function.Consumer;

/**
 * This class takes IsoLabel's as produced by ShortestPathTree, and produces the full edge geometry (
 * including pillar nodes) for each IsoLabel. This results in more edges being returned with more accurate
 * edge geometry, especially for curved roads with few intersections.
 */
public class PillarEdgeResolver implements Consumer<ShortestPathTree.IsoLabel> {
    Consumer<IsoLabel> consumer;
    Graph graph;

    public static class IsoLabel {
        public ShortestPathTree.IsoLabel original_label;
        public PointList pl;

        IsoLabel(PointList pl, ShortestPathTree.IsoLabel original_label) {
            this.original_label = original_label;
            this.pl = pl;
        }

        @Override
        public String toString() {
            return "PER.IsoLabel{" + original_label + ", " + pl + "} ";
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
        NodeAccess na = graph.getNodeAccess();


        if (label.edge == -1 || label.edge >= graph.getEdges()) {
            if (label.node < graph.getNodes()) {
                GHPoint point;
                if (na.is3D())
                    point = new GHPoint3D(na.getLat(label.node), na.getLon(label.node), na.getEle(label.node));
                else point = new GHPoint(na.getLat(label.node), na.getLon(label.node));

                PointList pl = new PointList();
                pl.add(point);
                IsoLabel this_label = new IsoLabel(pl, label);
            }
            return;
        }
        EdgeIteratorState edge = graph.getEdgeIteratorState(label.edge, label.node);

        final double allowed_distance = label.consumed_part;
        double consumed_distance = 0;

        if (allowed_distance < 0.005) {
            return;
        }

        PointList geom = edge.fetchWayGeometry(FetchMode.ALL);


        assertEquals(geom.getLat(0), na.getLat(label.parent.node));
        assertEquals(geom.getLon(0), na.getLon(label.parent.node));
        assertEquals(geom.getLat(geom.size() - 1), na.getLat(label.node));
        assertEquals(geom.getLon(geom.size() - 1), na.getLon(label.node));


        assert (DistanceCalcEarth.calcDistance(geom, geom.is3D()) + 1.0 >= allowed_distance);

        for (int i = 1; i < geom.size(); i++) {
            GHPoint3D prev_element = geom.get(i - 1);
            GHPoint3D element = geom.get(i);
            if (Math.abs(consumed_distance - allowed_distance) < 0.01) {
                // Delete the remaining PointList elements
                geom.trimToSize(i);
                break;
            }

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
                element.lat = intermediate.lat;
                element.lon = intermediate.lon;
                element.ele = intermediate.ele;
                consumed_distance += additional_distance;
            } else {
                consumed_distance += this_distance;
            }

        }

        IsoLabel this_label = new IsoLabel(geom, label);

        this.consumer.accept(this_label);

        assert Math.abs(allowed_distance - consumed_distance) < 0.5 : allowed_distance + " " + consumed_distance;
    }


}
