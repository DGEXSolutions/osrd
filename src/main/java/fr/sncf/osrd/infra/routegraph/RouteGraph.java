package fr.sncf.osrd.infra.routegraph;

import fr.sncf.osrd.infra.InvalidInfraException;
import fr.sncf.osrd.infra.TVDSection;
import fr.sncf.osrd.infra.signaling.Signal;
import fr.sncf.osrd.infra.trackgraph.Switch;
import fr.sncf.osrd.infra.trackgraph.SwitchPosition;
import fr.sncf.osrd.infra.trackgraph.TrackSection;
import fr.sncf.osrd.infra.trackgraph.Waypoint;
import fr.sncf.osrd.infra.waypointgraph.TVDSectionPath;
import fr.sncf.osrd.infra.waypointgraph.WaypointGraph;
import fr.sncf.osrd.utils.SortedArraySet;
import fr.sncf.osrd.utils.graph.DirNGraph;
import fr.sncf.osrd.utils.graph.EdgeDirection;
import fr.sncf.osrd.utils.graph.EdgeEndpoint;
import fr.sncf.osrd.utils.graph.UndirectedBiEdgeID;

import java.util.*;

public class RouteGraph extends DirNGraph<Route, Waypoint> {
    public final HashMap<String, Route> routeMap = new HashMap<>();

    @Override
    public List<Route> getNeighbors(Route route) {
        var lastTvdSectionPathIndex = route.tvdSectionsPaths.size() - 1;
        var lastTvdSectionPath = route.tvdSectionsPaths.get(lastTvdSectionPathIndex);
        var lastTvdSectionPathDir = route.tvdSectionsPathDirections.get(lastTvdSectionPathIndex);
        var node = getNode(lastTvdSectionPath.getEndNode(lastTvdSectionPathDir));
        var nodeDirection = lastTvdSectionPath.nodeDirection(lastTvdSectionPathDir, EdgeEndpoint.END);
        return node.getRouteNeighbors(nodeDirection);
    }

    /** Return the neighbors of the route at the start node */
    public List<Route> getIncomingNeighbors(Route route) {
        var firstTvdSectionPathIndex = 0;
        var firstTvdSectionPath = route.tvdSectionsPaths.get(firstTvdSectionPathIndex);
        var firstTvdSectionPathDir = route.tvdSectionsPathDirections.get(firstTvdSectionPathIndex);
        var node = getNode(firstTvdSectionPath.getStartNode(firstTvdSectionPathDir));
        var nodeDirection = firstTvdSectionPath.nodeDirection(firstTvdSectionPathDir, EdgeEndpoint.BEGIN);
        return node.getIncomingRouteNeighbors(nodeDirection);
    }

    public static class Builder {
        public RouteGraph routeGraph = new RouteGraph();
        public final WaypointGraph waypointGraph;

        /**
         * A helper to help build RouteGraph instances.
         */
        public Builder(WaypointGraph waypointGraph) {
            this.waypointGraph = waypointGraph;
            // Register nodes from the waypoint graph
            for (var node : waypointGraph.iterNodes())
                routeGraph.registerNode(node);
        }

        /** Add a route to the Route Graph */
        public Route makeRoute(
                String id,
                SortedArraySet<TVDSection> tvdSections,
                List<SortedArraySet<TVDSection>> releaseGroups,
                HashMap<Switch, SwitchPosition> switchesPosition,
                Waypoint entryPoint,
                Signal entrySignal
        ) throws InvalidInfraException {
            var length = 0;
            var tvdSectionsPath = new ArrayList<TVDSectionPath>();
            var tvdSectionsPathDirection = new ArrayList<EdgeDirection>();

            var waypoints = generateWaypointList(entryPoint, tvdSections, switchesPosition);

            // Find the list of tvd section path
            for (var i = 1; i < waypoints.size(); i++) {
                var startIndex = waypoints.get(i - 1).index;
                var endIndex = waypoints.get(i).index;
                var tvdSectionPath = waypointGraph.getTVDSectionPath(startIndex, endIndex);

                if (tvdSectionPath == null)
                    throw new InvalidInfraException(String.format(
                            "Route: '%s' couldn't find tvd section path: (%d, %d)", id, startIndex, endIndex));
                if (!tvdSections.contains(tvdSectionPath.tvdSection))
                    throw new InvalidInfraException(
                            String.format("Route '%s' has a tvd section path outside tvd section", id));

                if (tvdSectionPath.startNode == startIndex)
                    tvdSectionsPathDirection.add(EdgeDirection.START_TO_STOP);
                else
                    tvdSectionsPathDirection.add(EdgeDirection.STOP_TO_START);

                tvdSectionsPath.add(tvdSectionPath);
                length += tvdSectionPath.length;
            }

            // Checks that the release groups are consistent and complete
            var copiedTvdSections = new SortedArraySet<TVDSection>();
            for (var releaseGroup : releaseGroups) {
                for (var tvdSection : releaseGroup) {
                    if (!tvdSections.contains(tvdSection))
                        throw new InvalidInfraException(String.format(
                                "Route '%s' has a release group that doesn't match route's tvd sections", id));
                    copiedTvdSections.add(tvdSection);
                }
            }
            if (copiedTvdSections.size() != tvdSections.size())
                throw new InvalidInfraException(String.format(
                        "Route '%s' has a tvd section that is not part of any of its release groups", id));

            // Create route
            var route = new Route(
                    id,
                    routeGraph,
                    length,
                    releaseGroups,
                    tvdSectionsPath,
                    tvdSectionsPathDirection,
                    switchesPosition,
                    entrySignal);

            routeGraph.routeMap.put(id, route);

            // Link route to the starting waypoint
            var firstTVDSectionPath = tvdSectionsPath.get(0);
            var firstTVDSectionPathDir = tvdSectionsPathDirection.get(0);
            var startWaypoint = waypointGraph.getNode(firstTVDSectionPath.getStartNode(firstTVDSectionPathDir));
            var waypointDirection = firstTVDSectionPath.nodeDirection(firstTVDSectionPathDir, EdgeEndpoint.BEGIN);
            startWaypoint.getRouteNeighbors(waypointDirection).add(route);

            // Link route to the ending waypoint
            var lastTVDSectionPath = tvdSectionsPath.get(0);
            var lastTVDSectionPathDir = tvdSectionsPathDirection.get(0);
            var endWaypoint = waypointGraph.getNode(lastTVDSectionPath.getEndNode(lastTVDSectionPathDir));
            var endWaypointDirection = lastTVDSectionPath.nodeDirection(lastTVDSectionPathDir, EdgeEndpoint.END);
            endWaypoint.getIncomingRouteNeighbors(endWaypointDirection).add(route);
            
            // Link route to track sections and tvd sections
            double routeOffset = 0;
            for (int i = 0; i < route.tvdSectionsPaths.size(); i++) {
                var tvdSectionPath = route.tvdSectionsPaths.get(i);
                var tvdSectionPathDir = route.tvdSectionsPathDirections.get(i);
                tvdSectionPath.tvdSection.routeSubscribers.add(route);
                for (var trackSectionRange : tvdSectionPath.getTrackSections(tvdSectionPathDir)) {
                    var trackSection = trackSectionRange.edge;
                    var trackBegin = Math.min(trackSectionRange.getBeginPosition(), trackSectionRange.getEndPosition());
                    var trackEnd = Math.max(trackSectionRange.getBeginPosition(), trackSectionRange.getEndPosition());
                    var routeFragment = new TrackSection.RouteFragment(
                            route, routeOffset, trackBegin, trackEnd, trackSectionRange.direction);
                    trackSection.getRoutes(trackSectionRange.direction).insert(routeFragment);
                    routeOffset += trackSectionRange.length();
                }
            }
            return route;
        }

        public RouteGraph build() {
            return routeGraph;
        }

        private Set<Waypoint> findTvdWaypoints(TrackSection trackSection, EdgeDirection direction) {
            var res = new HashSet<Waypoint>();
            var waypoints = trackSection.waypoints;
            if (waypoints.size() > 0) {
                if (direction == EdgeDirection.START_TO_STOP)
                    res.add(waypoints.data.get(0).value);
                else
                    res.add(waypoints.data.get(waypoints.data.size() - 1).value);
                return res;
            }
            var neighbors = trackSection.getForwardNeighbors(direction);
            for (var neighbor : neighbors) {
                var neighborDirection = neighbor.getDirection(trackSection, direction);
                res.addAll(findTvdWaypoints(neighbor, neighborDirection));
                var backNeighbors = neighbor.getBackwardNeighbors(neighborDirection);
                for (var backNeighbor : backNeighbors) {
                    if (backNeighbor != trackSection) {
                        var backNeighborDirection = backNeighbor.getDirection(neighbor, neighborDirection.opposite());
                        res.addAll(findTvdWaypoints(backNeighbor, backNeighborDirection));
                    }
                }
            }
            return res;
        }

        /** Generates a set of waypoints on the route, based on its tvd sections and switch positions
         * We accumulate all the waypoints in all the tvd sections, then remove the ones in the other switch branches */
        private Set<Waypoint> getWaypointsOnRoute(
                Set<TVDSection> tvdSections,
                HashMap<Switch, SwitchPosition> switchesPosition
        ) throws InvalidInfraException {
            var res = new HashSet<Waypoint>();
            tvdSections.forEach(x -> res.addAll(x.waypoints));
            if (switchesPosition != null) {
                for (var entry : switchesPosition.entrySet()) {
                    var aSwitch = entry.getKey();
                    switch (entry.getValue()) {
                        case LEFT:
                            if (aSwitch.rightTrackSection.startNode == aSwitch.index)
                                res.removeAll(findTvdWaypoints(aSwitch.rightTrackSection, EdgeDirection.START_TO_STOP));
                            else
                                res.removeAll(findTvdWaypoints(aSwitch.rightTrackSection, EdgeDirection.STOP_TO_START));
                            break;
                        case RIGHT:
                            if (aSwitch.leftTrackSection.startNode == aSwitch.index)
                                res.removeAll(findTvdWaypoints(aSwitch.leftTrackSection, EdgeDirection.START_TO_STOP));
                            else
                                res.removeAll(findTvdWaypoints(aSwitch.leftTrackSection, EdgeDirection.STOP_TO_START));
                            break;
                        case MOVING:
                            throw new InvalidInfraException("route: switch required position cannot be MOVING");
                    }
                }
            }
            return res;
        }

        /** Generates a list of waypoints present on the route in the right order */
        private List<Waypoint> generateWaypointList(
                Waypoint startPoint,
                Set<TVDSection> tvdSections,
                HashMap<Switch, SwitchPosition> switchesPosition
        ) throws InvalidInfraException {
            var currentPoint = startPoint;
            var waypoints = new ArrayList<Waypoint>();

            var waypointsToVisit = getWaypointsOnRoute(tvdSections, switchesPosition);

            // We order the points by finding which one is adjacent to the previous point
            // until there is no more point to visit
            while (true) {
                waypointsToVisit.remove(currentPoint);
                waypoints.add(currentPoint);
                if (waypointsToVisit.isEmpty())
                    return waypoints;
                for (var waypoint : waypointsToVisit) {
                    var pair = UndirectedBiEdgeID.from(waypoint.index, currentPoint.index);
                    if (waypointGraph.tvdSectionPathMap.containsKey(pair)) {
                        currentPoint = waypoint;
                        break;
                    }
                }
                if (!waypointsToVisit.contains(currentPoint)) {
                    // we didn't find a pair
                    var errorMsg = "Route: can't find a waypoint list, are the tvd sections connected "
                            + "and the switches set to the right positions?";
                    throw new InvalidInfraException(errorMsg);
                }
            }
        }

    }
}
