package com.graphhopper.util;
import com.bedatadriven.jackson.datatype.jts.JtsModule;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.linemerge.LineMerger;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.locationtech.proj4j.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class EdgeBuffering {

    private static final Logger logger = LoggerFactory.getLogger(EdgeBuffering.class);
    private static final CRSFactory crsFactory = new CRSFactory();

    private static final CoordinateReferenceSystem longLatCRS = crsFactory.createFromParameters("4326", "+proj=longlat +datum=WGS84 +no_defs");

    private static final CoordinateReferenceSystem azimuthEquivalentNAD83CRS = crsFactory.createFromParameters("4269", "+proj=aeqd +datum=NAD83 +no_defs");

    public List<LineString> edges;

    private static List<LineString> buildGeometryFromLines(List<List<PointList>> lines) {
        GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326, new PackedCoordinateSequenceFactory());

        List<LineString> geoms = new ArrayList<>();
        for (List<PointList> pointListList : lines) {
            List<LineString> currentGeom = new ArrayList<>();
            for (PointList line : pointListList) {
                // Use LineMerger to merge line strings
                Coordinate[] coordinates = new Coordinate[line.size()];
                for (int i = 0; i < line.size(); i += 1) {
                    Coordinate latLng = new Coordinate(line.getLon(i), line.getLat(i));
                    Coordinate nad83Projected = transformLatLongToNAD83(latLng, false);
                    coordinates[i] = nad83Projected;
                }
                LineString ls = new LineString(new CoordinateArraySequence(coordinates), geometryFactory);

                currentGeom.add(ls);
            }

            LineMerger lineMerger = new LineMerger();
            lineMerger.add(currentGeom);
            Collection<LineString> merged = lineMerger.getMergedLineStrings();

            geoms.addAll(merged);
        }

        return geoms;
    }


    public EdgeBuffering(List<List<PointList>> edges) {
        logger.info("Buffering edges: " + edges.size());

        this.edges = buildGeometryFromLines(edges);
    }


    public String buildEdgeBufferGeoJSON(double distance) {
        Geometry buffered = buildEdgeBuffer(distance);
        return toGeoJSON(buffered);
    }

    public Geometry unionGeos(List<? extends Geometry> geos) {
        UnaryUnionOp unionOp = new UnaryUnionOp(geos);
        return unionOp.union();
    }
    public Geometry buildEdgeBuffer(double distance) {
        // Builds an edge buffer of `distance` meters around the list of edges
        BufferParameters params = new BufferParameters();
        params.setSimplifyFactor(0.1);
        params.setQuadrantSegments(4);

//        MultiLineString x = new MultiLineString(this.edges.toArray(new LineString[0]), new GeometryFactory());
//
//        for (Coordinate c : x.getCoordinates()) {
//            Coordinate transformed = transformLatLongToNAD83(c, true);
//            c.x = transformed.x;
//            c.y = transformed.y;
//        }
//
//        String gj = toGeoJSON(x);


        List<Geometry> buffered_geoms = new ArrayList<>(this.edges.size());
        final int WINDOW_SIZE = 20;
        logger.info("Using window size of " + WINDOW_SIZE);
        ExecutorService executor = Executors.newWorkStealingPool();

        List<Future<Geometry>> futures = new ArrayList<>();
        StopWatch sw = new StopWatch().start();
        for (int i = 0; i < this.edges.size(); i += WINDOW_SIZE) {
            final int localI = i;
            Future<Geometry> fut = executor.submit(() -> {
                Geometry g = unionGeos(this.edges.subList(localI, Math.min(localI + WINDOW_SIZE, this.edges.size())));

                Geometry buffered = BufferOp.bufferOp(g, distance, params);

                return DouglasPeuckerSimplifier.simplify(buffered, 8);
            });
            futures.add(fut);
        }

        executor.shutdown();

        for (Future<Geometry> fut : futures) {
            try {
                buffered_geoms.add(fut.get());
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
        logger.info("Buffered " + this.edges.size() + " edges into " + buffered_geoms.size() + " buffers. Took " + sw.stop().getSeconds() + " seconds.");

        sw = new StopWatch().start();

        Geometry unioned = unionGeos(buffered_geoms);

        logger.info("Unioned " + buffered_geoms.size() + " buffers into " + unioned.getNumGeometries() + " geometries. Took " + sw.stop().getSeconds() + " seconds.");

        for (Coordinate c : unioned.getCoordinates()) {
            Coordinate transformed = transformLatLongToNAD83(c, true);
            c.x = transformed.x;
            c.y = transformed.y;
        }
        return unioned;
    };

    public static String toGeoJSON(Geometry g) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JtsModule());
        try {
            return objectMapper.writeValueAsString(g);
        } catch (JsonProcessingException exc) {
            throw new RuntimeException("Failed to serialize to geojson");
        }
    }

    private static Coordinate transformLatLongToNAD83(Coordinate inputCoordinate, boolean inverse) {
        double longitude = inputCoordinate.x;
        double latitude = inputCoordinate.y;

        // Create the coordinate transformation
        CoordinateTransformFactory transformFactory = new CoordinateTransformFactory();

        CoordinateTransform transform;
        if (inverse) {
            transform = transformFactory.createTransform(azimuthEquivalentNAD83CRS, longLatCRS);
        } else {
            transform = transformFactory.createTransform(longLatCRS, azimuthEquivalentNAD83CRS);
        }

        // Perform the coordinate transformation
        ProjCoordinate inputProjCoord = new ProjCoordinate(longitude, latitude);
        ProjCoordinate outputProjCoord = new ProjCoordinate();
        transform.transform(inputProjCoord, outputProjCoord);

        // Extract the transformed longitude and latitude
        double transformedLongitude = outputProjCoord.x;
        double transformedLatitude = outputProjCoord.y;

        return new Coordinate(transformedLongitude, transformedLatitude);
    }
}
