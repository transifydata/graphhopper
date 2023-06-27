package com.graphhopper.resources;

import com.graphhopper.util.shapes.GHPoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class BatchSPTRequest {
    List<SPTPoint> points;

    public List<SPTPoint> getPoints() {
        return points;
    }

    public void setPoints(List<double[]> points) {
        this.points = points.stream().map(arr -> {
            GHPoint point = new GHPoint(arr[0], arr[1]);

            if (arr.length == 2)
                return new SPTPoint(point, -1);
            else if (arr.length == 3){
                return new SPTPoint(point, arr[2]);
            } else {
                throw new IllegalArgumentException("Invalid number of arguments for point: " + Arrays.toString(arr));
            }
        }).collect(Collectors.toList());
    }


    public BatchSPTRequest() {
        this.points = new ArrayList<>();
    }

}
