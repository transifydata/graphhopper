package com.graphhopper.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class EdgeBufferingTest {

    @BeforeEach
    void setUp() {
    }

    @AfterEach
    void tearDown() {
    }

//    @Test
//    public void t1() throws Exception {
//        PointList pointList = new PointList();
//        pointList.add(43.47459906714954, -80.54640749722667);
//        pointList.add(43.47703385659298, -80.53985776969917);
//
//
//        PointList pointList1 = new PointList();
//        pointList1.add(43.47872641443335, -80.53512885325418);
//        pointList1.add(43.4817632985735, -80.52618144585655);
//
//        PointList pointList2 = new PointList();
//        pointList2.add(43.455883899827406, -80.50789905342697);
//        pointList2.add(43.466839150046596, -80.51672285575728);
//        EdgeBuffering eb = new EdgeBuffering(Arrays.asList(pointList, pointList1, pointList2));
//        Geometry buffered = eb.buildEdgeBuffer(300.00);
//        System.out.printf("%s\n", EdgeBuffering.toGeoJSON(buffered));
//    }
}
