package com.graphhopper.util;
import com.bedatadriven.jackson.datatype.jts.JtsModule;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.locationtech.proj4j.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class EdgeBuffering {

    private static final Logger logger = LoggerFactory.getLogger(EdgeBuffering.class);
    private static final CRSFactory crsFactory = new CRSFactory();

    private static final CoordinateReferenceSystem longLatCRS = crsFactory.createFromParameters("4326", "+proj=longlat +datum=WGS84 +no_defs");

    private static final CoordinateReferenceSystem azimuthEquivalentNAD83CRS = crsFactory.createFromParameters("4269", "+proj=aeqd +datum=NAD83 +no_defs");

    public List<Geometry> edges;

    private static List<Geometry> buildGeometryFromLines(List<PointList> lines) {
        GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326, new PackedCoordinateSequenceFactory());

        List<Geometry> geoms = new ArrayList<>();
        for (PointList line : lines) {
            Coordinate[] coordinates = new Coordinate[line.size()];
            for (int i = 0; i < line.size(); i += 1) {
                Coordinate latLng = new Coordinate(line.getLon(i), line.getLat(i));
                Coordinate nad83Projected = transformLatLongToNAD83(latLng, false);
                coordinates[i] = nad83Projected;
            }
            LineString ls = new LineString(new CoordinateArraySequence(coordinates), geometryFactory);
            geoms.add(ls);
        }

        return geoms;
    }


    public EdgeBuffering(List<PointList> edges) {
        logger.info("Buffering edges: " + edges.size());

        this.edges = buildGeometryFromLines(edges);
    }


    public String buildEdgeBufferGeoJSON(double distance) {
        Geometry buffered = buildEdgeBuffer(distance);
        return toGeoJSON(buffered);
    }

    public Geometry buildEdgeBuffer(double distance) {
        // Builds an edge buffer of `distance` meters around the list of edges
        BufferParameters params = new BufferParameters();
        params.setSimplifyFactor(0.02);

        List<Geometry> buffered_geoms = new ArrayList<>(this.edges.size());
        for (Geometry g : this.edges) {
            buffered_geoms.add(BufferOp.bufferOp(g, distance, params));
        }

        UnaryUnionOp unionOp = new UnaryUnionOp(buffered_geoms);
        Geometry unioned = unionOp.union();

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
