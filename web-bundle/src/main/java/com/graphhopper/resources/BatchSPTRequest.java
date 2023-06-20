package com.graphhopper.resources;

import com.graphhopper.util.shapes.GHPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class BatchSPTRequest {
    List<GHPoint> points;

    public List<GHPoint> getPoints() {
        return points;
    }

    public void setPoints(List<double[]> points) {
        this.points = points.stream().map(arr -> new GHPoint(arr[0], arr[1])).collect(Collectors.toList());
    }


    public BatchSPTRequest() {
        this.points = new ArrayList<>();
    }

}
