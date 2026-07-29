package com.graphhopper.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the fork's isochrone edge buffering: connected polylines are merged, then
 * buffered in a metric (azimuthal equidistant) projection in parallel windows and unioned
 * into a single polygon, returned in lon/lat.
 */
class EdgeBufferingTest {

    private static PointList line(double... latLonPairs) {
        PointList pl = new PointList();
        for (int i = 0; i < latLonPairs.length; i += 2)
            pl.add(latLonPairs[i], latLonPairs[i + 1]);
        return pl;
    }

    @Test
    public void connectedLinesAreMerged() {
        // A and B share an endpoint and must merge into one LineString; C is disjoint
        PointList a = line(43.4700, -80.5400, 43.4710, -80.5390);
        PointList b = line(43.4710, -80.5390, 43.4720, -80.5380);
        PointList c = line(43.4800, -80.5300, 43.4810, -80.5290);

        List<LineString> merged = EdgeBuffering.buildGeometryFromLines(
                List.of(List.of(a, b, c)), false);

        assertEquals(2, merged.size());
        int totalPoints = merged.stream().mapToInt(LineString::getNumPoints).sum();
        // merged A+B has 3 points (shared endpoint deduplicated), C keeps 2
        assertEquals(5, totalPoints);
    }

    @Test
    public void bufferCoversInputGeometry() throws Exception {
        // more than one WINDOW_SIZE (20) worth of disjoint segments so the parallel
        // windowed buffering path is exercised
        List<PointList> segments = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            double lat = 43.4000 + 0.002 * i;
            segments.add(line(lat, -80.5400, lat, -80.5300));
        }

        EdgeBuffering eb = new EdgeBuffering(List.of(segments));
        Geometry buffered = eb.buildEdgeBuffer(200.0);
        assertFalse(buffered.isEmpty());
        assertTrue(buffered.isValid());

        // the returned geometry is in lon/lat with valid envelopes, so spatial predicates
        // work directly: every input endpoint must be inside the 200m buffer
        GeometryFactory gf = new GeometryFactory();
        for (PointList segment : segments) {
            for (int i = 0; i < segment.size(); i++) {
                Point p = gf.createPoint(new Coordinate(segment.getLon(i), segment.getLat(i)));
                assertTrue(buffered.covers(p), "buffer does not cover input point " + p);
            }
        }
    }

    // builds JTS polygons (exterior rings only) from a GeoJSON Polygon/MultiPolygon string
    // and checks the coordinates are plausible lon/lat values
    private static List<Geometry> parseGeoJsonPolygons(String geoJson) throws Exception {
        JsonNode root = new ObjectMapper().readTree(geoJson);
        GeometryFactory gf = new GeometryFactory();
        List<Geometry> result = new ArrayList<>();
        String type = root.get("type").asText();
        JsonNode coords = root.get("coordinates");
        List<JsonNode> polygons = new ArrayList<>();
        if (type.equals("Polygon"))
            polygons.add(coords);
        else if (type.equals("MultiPolygon"))
            coords.forEach(polygons::add);
        else
            fail("unexpected geometry type " + type);
        for (JsonNode polygon : polygons) {
            JsonNode ring = polygon.get(0); // exterior ring
            Coordinate[] shell = new Coordinate[ring.size()];
            for (int i = 0; i < ring.size(); i++) {
                double lon = ring.get(i).get(0).asDouble();
                double lat = ring.get(i).get(1).asDouble();
                assertTrue(lon > -81.5 && lon < -79.5, "longitude out of range: " + lon);
                assertTrue(lat > 42.5 && lat < 44.5, "latitude out of range: " + lat);
                shell[i] = new Coordinate(lon, lat);
            }
            result.add(gf.createPolygon(shell));
        }
        return result;
    }

    @Test
    public void disjointSegmentsProduceMultiPolygon() {
        PointList near = line(43.4700, -80.5400, 43.4705, -80.5395);
        PointList far = line(43.9000, -80.1000, 43.9005, -80.0995);
        EdgeBuffering eb = new EdgeBuffering(List.of(List.of(near, far)));
        Geometry buffered = eb.buildEdgeBuffer(100.0);
        assertEquals(2, buffered.getNumGeometries(), "far-apart segments should stay separate polygons");
    }

    @Test
    public void geoJsonSerialization() throws Exception {
        PointList a = line(43.4700, -80.5400, 43.4710, -80.5390);
        EdgeBuffering eb = new EdgeBuffering(List.of(List.of(a)));
        String geoJson = eb.buildEdgeBufferGeoJSON(150.0);

        JsonNode node = new ObjectMapper().readTree(geoJson);
        assertTrue(node.has("type"));
        assertTrue(node.get("type").asText().contains("Polygon"));
        assertTrue(node.has("coordinates"));

        // parses the polygons and validates the serialized coordinates are lon/lat
        assertFalse(parseGeoJsonPolygons(geoJson).isEmpty());
    }
}
