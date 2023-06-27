package com.graphhopper.resources;

import com.graphhopper.util.shapes.GHPoint;

public class SPTPoint {
    public GHPoint point;
    public double distance_limit;

    public SPTPoint(GHPoint point, double distance_limit) {
        this.point = point;
        this.distance_limit = distance_limit;
    }
}
