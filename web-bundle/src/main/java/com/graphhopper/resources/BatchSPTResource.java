package com.graphhopper.resources;

import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;
import com.graphhopper.http.ProfileResolver;
import com.graphhopper.isochrone.algorithm.PillarEdgeResolver;
import com.graphhopper.isochrone.algorithm.ShortestPathTree;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.util.DefaultSnapFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.GHPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.util.*;

import static com.graphhopper.resources.RouteResource.removeLegacyParameters;
import static com.graphhopper.routing.util.TraversalMode.EDGE_BASED;
import static com.graphhopper.routing.util.TraversalMode.NODE_BASED;

@Path("batch-spt")
public class BatchSPTResource {

    private static final Logger logger = LoggerFactory.getLogger(BatchSPTResource.class);

    private final GraphHopper graphHopper;
    private final ProfileResolver profileResolver;

    @Inject
    public BatchSPTResource(GraphHopper graphHopper, ProfileResolver profileResolver, EncodingManager encodingManager) {
        this.graphHopper = graphHopper;
        this.profileResolver = profileResolver;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response doPost(
            @Context UriInfo uriInfo,
            BatchSPTRequest request,
            @QueryParam("profile") String profileName,
            @QueryParam("reverse_flow") @DefaultValue("false") boolean reverseFlow,
            @QueryParam("distance_limit") @DefaultValue("-1") OptionalLong distanceInMeter,
            @QueryParam("include_overextended_edges") @DefaultValue("true") boolean includeOverextended,
            @QueryParam("calculate_buffer_distance") OptionalDouble calculateBufferDistance
    ) {
        StopWatch sw = new StopWatch().start();

        PMap hintsMap = new PMap();
        RouteResource.initHints(hintsMap, uriInfo.getQueryParameters());
        hintsMap.putObject(Parameters.CH.DISABLE, true);
        hintsMap.putObject(Parameters.Landmark.DISABLE, true);

        PMap profileResolverHints = new PMap(hintsMap);
        profileResolverHints.putObject("profile", profileName);
        profileName = profileResolver.resolveProfile(profileResolverHints);
        removeLegacyParameters(hintsMap);

        Profile profile = graphHopper.getProfile(profileName);
        if (profile == null)
            throw new IllegalArgumentException("The requested profile '" + profileName + "' does not exist");
        LocationIndex locationIndex = graphHopper.getLocationIndex();
        BaseGraph graph = graphHopper.getBaseGraph();
        Weighting weighting = graphHopper.createWeighting(profile, hintsMap);
        BooleanEncodedValue inSubnetworkEnc = graphHopper.getEncodingManager().getBooleanEncodedValue(Subnetwork.key(profileName));

        List<List<PointList>> edges = new ArrayList<>(request.points.size());

        for (GHPoint point : request.points) {
            ArrayList<PointList> curList = new ArrayList<>();
            double lat = point.lat;
            double lon = point.lon;
            Snap snap = locationIndex.findClosest(lat, lon, new DefaultSnapFilter(weighting, inSubnetworkEnc));
            if (!snap.isValid())
                throw new IllegalArgumentException("Point not found:" + point);


            QueryGraph queryGraph = QueryGraph.create(graph, snap);

            TraversalMode traversalMode = profile.isTurnCosts() ? EDGE_BASED : NODE_BASED;
            ShortestPathTree shortestPathTree = new ShortestPathTree(queryGraph, queryGraph.wrapWeighting(weighting), reverseFlow, traversalMode);
            shortestPathTree.setIncludeOverextendedEdges(includeOverextended);

            if (distanceInMeter.orElseThrow(() -> new IllegalArgumentException("query param distance_limit is not a number.")) > 0) {
                shortestPathTree.setDistanceLimit(distanceInMeter.getAsLong());
            } else {
                throw new RuntimeException("distance_limit must not be null and be greater than 0");
            }

            PillarEdgeResolver pillarEdgeResolver = new PillarEdgeResolver(l -> curList.add(l.pl), graph);
            shortestPathTree.search(snap.getClosestNode(), pillarEdgeResolver);

            edges.add(curList);
        }

        Object response;
        if (calculateBufferDistance.isPresent()) {
            EdgeBuffering eb = new EdgeBuffering(edges);
            response = eb.buildEdgeBufferGeoJSON(calculateBufferDistance.getAsDouble());
        } else {
            // Just return the edges
            // Columns we want are: prev_latitude, prev_longitude, latitude, longitude
            // Build an array with these columns for each IsoLabel
            List<double[]> rows = new ArrayList<>();

            for (List<PointList> pointLists : edges) {
                for (PointList pointList : pointLists) {
                    GHPoint prev_point = null;
                    for (GHPoint point : pointList) {
                        if (prev_point != null)
                            rows.add(new double[]{prev_point.lat, prev_point.lon, point.lat, point.lon});
                        prev_point = point;
                    }
                }
            }

            // Return rows as JSON
            response = rows;
        }

        logger.info("took: " + sw.stop().getSeconds());
        return Response.ok(response).build();
    }


}
