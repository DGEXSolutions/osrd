package fr.sncf.osrd.infra.parsing.rsl;

import fr.sncf.osrd.infra.InvalidInfraException;
import fr.sncf.osrd.railjson.schema.common.ID;
import fr.sncf.osrd.railjson.schema.infra.*;
import fr.sncf.osrd.railjson.schema.infra.railscript.RJSRSExpr;
import fr.sncf.osrd.railjson.schema.infra.railscript.RJSRSFunction;
import fr.sncf.osrd.railjson.schema.infra.signaling.RJSAspect;
import fr.sncf.osrd.railjson.schema.infra.trackobjects.RJSBufferStop;
import fr.sncf.osrd.railjson.schema.infra.trackobjects.RJSRouteWaypoint;
import fr.sncf.osrd.railjson.schema.infra.trackobjects.RJSSignal;
import fr.sncf.osrd.railjson.schema.infra.trackobjects.RJSTrainDetector;
import fr.sncf.osrd.railjson.schema.infra.trackranges.RJSOperationalPointPart;
import fr.sncf.osrd.railjson.schema.infra.trackranges.RJSSpeedSectionPart;
import fr.sncf.osrd.utils.UnionFind;
import fr.sncf.osrd.utils.graph.ApplicableDirection;
import fr.sncf.osrd.utils.graph.EdgeEndpoint;
import fr.sncf.osrd.utils.XmlNamespaceCleaner;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import java.util.*;

public final class RslParser {
    /**
     * Initialises a new infrastructure from a Rsl file.
     * @return the parsed infrastructure
     */
    public static RJSInfra parse(String inputPath) throws InvalidInfraException {
        Document document;
        try {
            document = new SAXReader().read(inputPath);
        } catch (DocumentException e) {
            throw new InvalidInfraException("invalid XML", e);
        }

        // remove xml namespace tags, as these prevent using xpath
        document.accept(new XmlNamespaceCleaner());

        // parse all pieces of track
        var edges = Edge.parseEdges(document);

        // create and fill the root RailJSON structure:
        // create the track sections map and the speed sections list
        // create a map of all edges connected to a given node
        var rjsTrackSections = new HashMap<String, RJSTrackSection>();
        var rjsSpeedSections = new ArrayList<RJSSpeedSection>();
        var nodeMap = new HashMap<String, ArrayList<Edge>>();
        // parse the speed indicators
        var speedIndicators = speedIndicatorsParse(document);
        for (var edge : edges) {
            rjsTrackSections.put(edge.id, edge);
            speedSectionParse(rjsSpeedSections, rjsTrackSections, edge, speedIndicators);
            createNodeMap(nodeMap, edge);
        }
        // parse the signals
        var signalsNodesMap = signalsParse(document, nodeMap);

        // create RailJSON switches and track section links
        var rjsTrackSectionLinks = new HashMap<String, RJSTrackSectionLink>();
        var rjsSwitches = switchParse(document, nodeMap, rjsTrackSectionLinks);

        // create track section links for all the nodes not being switches
        for (var entry : nodeMap.entrySet()) {
            var neighbors = entry.getValue();
            if (neighbors.size() != 2)
                continue;
            addTrackSectionLinks(neighbors.get(0), neighbors.get(1),
                    entry.getKey(), rjsTrackSectionLinks);
        }

        var rjsOperationalPoints = timingPointsParse(document, nodeMap);
        var blockSections = BlockSection.parseBlockSection(document);
        var trainDetectorsMap = tdesParse(document, blockSections, nodeMap);
        var rjsTvdSections = tvdSectionsParse(blockSections, trainDetectorsMap, nodeMap);
        var rjsRoutes = routeParse(blockSections, trainDetectorsMap, nodeMap, rjsTvdSections, rjsSwitches);

        List<RJSAspect> rjsAspects = null;
        List<RJSRSFunction> rjsSignalFunctions = null;

        return new RJSInfra(
                rjsTrackSections.values(),
                rjsTrackSectionLinks.values(),
                rjsSwitches,
                rjsOperationalPoints,
                rjsTvdSections,
                rjsRoutes,
                rjsSpeedSections,
                rjsAspects,
                rjsSignalFunctions
        );
    }

    private static HashMap<String, RJSSignal> signalsParse(Document document,
                                                           HashMap<String, ArrayList<Edge>> nodeMap)
            throws InvalidInfraException {
        var signalsMap = new HashMap<String, RJSSignal>();
        var nodes = document.selectNodes("/line/nodes/signal");
        for (var node : nodes) {
            var signal = (Element) node;
            var signalNodeID = signal.attributeValue("nodeID");
            var signaltype = signal.attributeValue("type");
            var signalID = signal.attributeValue("name");
            RJSRSExpr exp = null;
            var rjsSignal = new RJSSignal(signalID, null, 0, 400, exp);
            signalsMap.put(signalNodeID, rjsSignal);

            // link tracks sections back to signal
            for (var edge : nodeMap.get(signalNodeID)) {
                var endSigPoint = findEndpoint(edge, signalNodeID);
                if (endSigPoint.endpoint == EdgeEndpoint.BEGIN) {
                    edge.signals.add(rjsSignal);
                    break;
                }
            }
        }
        return signalsMap;
    }

    private static ArrayList<String> speedIndicatorsParse(Document document) {
        var speedIndicatorsList = new ArrayList<String>();
        var nodes = document.selectNodes("/line/nodes/speedIndicator");
        for (var node : nodes) {
            var speedIndicator = (Element) node;
            var speedIndicatorID = speedIndicator.attributeValue("nodeID");
            speedIndicatorsList.add(speedIndicatorID);
        }
        return speedIndicatorsList;
    }

    /**
     * Create the route from the tvd sections
     */
    private static ArrayList<RJSRoute> routeParse(ArrayList<BlockSection> blockSections,
                                                  HashMap<String, RJSTrainDetector> trainDetectorsMap,
                                                  HashMap<String, ArrayList<Edge>> nodeMap,
                                                  ArrayList<RJSTVDSection> rjsTvdSections,
                                                  ArrayList<RJSSwitch> rjsSwitches) {
        var rjsRoutes = new ArrayList<RJSRoute>();
        var edgesMap = new HashMap<RJSTrackSection, Integer>();

        var uf = createUnionFind(edgesMap, nodeMap, blockSections, trainDetectorsMap);
        var trackSectionToGroup = new ArrayList<Integer>();
        uf.minimize(trackSectionToGroup);

        // Create the route end put attributes into
        for (var blockSection : blockSections) {
            var routeID = blockSection.getid();
            var nodesId = blockSection.getnodesId();
            var switchPosition = parseSwitchPosition(rjsSwitches, nodesId);
            var trackSections = findTrackSectionsFromNodeList(nodesId, nodeMap);
            // Find the Tvd Sections and the waypoints (train detectors) into the route
            var releaseGroups = new ArrayList<Set<ID<RJSTVDSection>>>();
            List<ID<RJSRouteWaypoint>> waypoints = new ArrayList<>();
            var tvdSections = new HashSet<ID<RJSTVDSection>>();
            for (var trackSection : trackSections) {
                // Find in witch TVD section EACH edge is
                Edge edge = (Edge) trackSection;
                var ufparent = edgesMap.get(edge);
                var itsTvdSectionID = trackSectionToGroup.get(ufparent);
                var tvdSection = new ID<RJSTVDSection>(String.valueOf(itsTvdSectionID));
                if (! tvdSections.contains(tvdSection)) {
                    tvdSections.add(tvdSection);
                    var releasedGroup = new HashSet<ID<RJSTVDSection>>();
                    releasedGroup.add(tvdSection);
                    releaseGroups.add(releasedGroup);
                }
                waypointsParse(waypoints, edge, rjsTvdSections.get(itsTvdSectionID).trainDetectors);
            }
            var firstEdge = trackSections.get(0);
            var entrysignal = firstEdge.signals.get(0);
            var entrysignalID = new ID<RJSSignal>(entrysignal.id);
            var rjsRoute = new RJSRoute(routeID, switchPosition, waypoints, releaseGroups, entrysignalID);
            rjsRoutes.add(rjsRoute);
        }
        return rjsRoutes;
    }

    private static UnionFind createUnionFind(HashMap<RJSTrackSection, Integer> edgesMap,
                                             HashMap<String, ArrayList<Edge>> nodeMap,
                                             ArrayList<BlockSection> blockSections,
                                             HashMap<String, RJSTrainDetector> trainDetectorsMap) {
        var uf = new UnionFind();
        for (var blockSection : blockSections) {
            // pass from nodes to TrackSections
            var nodesId = blockSection.getnodesId();
            var trackSections = findTrackSectionsFromNodeList(nodesId, nodeMap);
            // create an union find group for all the track sections in the BS
            for (int i = 0; i < trackSections.size(); i++) {
                Edge edge = (Edge) trackSections.get(i);
                if (edgesMap.get(edge) == null)
                    edgesMap.put(edge, uf.newGroup());

                // merge this group with the last group unless the edge's starting node is a detector
                var beginEdgeNodeID = nodesId[i];
                if (i == 0 || trainDetectorsMap.get(beginEdgeNodeID) != null)
                    continue;
                Edge previousEdge = (Edge) trackSections.get(i - 1);
                int groupA = edgesMap.get(edge);
                int groupB = edgesMap.get(previousEdge);
                uf.union(groupA, groupB);
            }
        }
        return uf;
    }

    private static HashMap<ID<RJSSwitch>, RJSSwitch.Position> parseSwitchPosition(
                                            ArrayList<RJSSwitch> switches,
                                            String[] nodesID) {
        // create switch map
        var switchMap = new HashMap<String, RJSSwitch>();
        for (var rjsSwitch : switches) {
            switchMap.put(rjsSwitch.id, rjsSwitch);
        }
        // Create switch Position map
        var switchPosition = new HashMap<ID<RJSSwitch>, RJSSwitch.Position>();
        for (var nodeID : nodesID) {
            if (switchMap.get(nodeID) == null)
                continue;

            var position = findSwitchPosition(switchMap.get(nodeID), nodesID);
            switchPosition.put(ID.from(switchMap.get(nodeID)), position);
        }
        return switchPosition;
    }

    private static RJSSwitch.Position findSwitchPosition(RJSSwitch rjsSwitch, String[] nodesID) {
        var nodeSwitchID = rjsSwitch.id;
        var leftEdgeID = rjsSwitch.left.section;
        var nodesLeftEdgeID = leftEdgeID.id.split("-");
        String leftNodeID = nodesLeftEdgeID[0];
        if (nodesLeftEdgeID[0].equals(nodeSwitchID))
            leftNodeID = nodesLeftEdgeID[1];

        String previousNodeID = null;
        String nextNodeID = null;
        for (int i = 1; i < nodesID.length - 1; i++) {
            if (nodesID[i].equals(nodeSwitchID)) {
                previousNodeID = nodesID[i - 1];
                nextNodeID = nodesID[i + 1];
                break;
            }
        }
        if (previousNodeID.equals(leftNodeID) || nextNodeID.equals(leftNodeID))
            return RJSSwitch.Position.LEFT;
        return RJSSwitch.Position.RIGHT;
    }

    private static void waypointsParse(List<ID<RJSRouteWaypoint>> waypoints,
                                       Edge edge,
                                       Collection<ID<RJSTrainDetector>> trainDetectors) {
        List<ID<RJSTrainDetector>> listTrainDetectorsID = new ArrayList<>(trainDetectors);
        // for each edge check if start and end nodes are train detectors of the TVD section
        // if yes and if they are not already into, add to waypoints
        for (var trainDetectorID : listTrainDetectorsID) {
            ID<RJSRouteWaypoint> waypointID = ID.fromID(trainDetectorID);
            var nodeID = waypointID.id.split("_")[1];
            if (nodeID.equals(edge.getEndNodeID())
                    || nodeID.equals(edge.getStartNodeID()))
                if (! waypoints.contains(waypointID))
                    waypoints.add(waypointID);
        }
        return;
    }

    /**
     * Construct the TVD Sections from the rls block sections
     * @return the TVD sections list for RJS
     */
    private static ArrayList<RJSTVDSection> tvdSectionsParse(ArrayList<BlockSection> blockSections,
                                                             HashMap<String, RJSTrainDetector> trainDetectorsMap,
                                                             HashMap<String, ArrayList<Edge>> nodeMap) {
        var rjsTvdSections = new ArrayList<RJSTVDSection>();
        var edgesMap = new HashMap<RJSTrackSection, Integer>();

        var uf = createUnionFind(edgesMap, nodeMap, blockSections, trainDetectorsMap);

        var trackSectionToGroup = new ArrayList<Integer>();
        var numberOfTVD = uf.minimize(trackSectionToGroup);
        // create a TVD section for each union find group
        for (int i = 0; i < numberOfTVD; i++) {
            var trainDetectors = new HashSet<ID<RJSTrainDetector>>();
            ArrayList<ID<RJSBufferStop>> bufferStops = null;
            rjsTvdSections.add(new RJSTVDSection(String.valueOf(i), false, trainDetectors, bufferStops));
        }

        // Put attributes into TVD sections
        for (var blockSection : blockSections) {
            var nodesId = blockSection.getnodesId();
            var trackSections = findTrackSectionsFromNodeList(nodesId, nodeMap);

            //find in witch TVD section EACH edge is
            for (var trackSection : trackSections) {
                Edge edge = (Edge) trackSection;
                var ufparent = edgesMap.get(edge);
                var itsTvdSectionID = trackSectionToGroup.get(ufparent);

                // set its Berthing Track
                rjsTvdSections.get(itsTvdSectionID).isBerthingTrack = blockSection.getisBerthingTrack();

                // Add the Train detectors to the TVD section if there are
                var beginEdgeNodeID = edge.getStartNodeID();
                var tdeBeginID = new ID<RJSTrainDetector>("tde_" + beginEdgeNodeID);
                if (trainDetectorsMap.get(beginEdgeNodeID) != null
                        &&  ! rjsTvdSections.get(itsTvdSectionID).trainDetectors.contains(tdeBeginID))
                    rjsTvdSections.get(itsTvdSectionID).trainDetectors.add(tdeBeginID);
                var endEdgeNodeID = edge.getEndNodeID();
                var tdeEndID = new ID<RJSTrainDetector>("tde_" + endEdgeNodeID);
                if (trainDetectorsMap.get(endEdgeNodeID) != null
                        &&  ! rjsTvdSections.get(itsTvdSectionID).trainDetectors.contains(tdeEndID))
                    rjsTvdSections.get(itsTvdSectionID).trainDetectors.add(tdeEndID);
            }
        }
        return rjsTvdSections;
    }

    /**
     * Parse and create the train detectors
     * */
    private static HashMap<String, RJSTrainDetector> tdesParse(Document document,
                                                               ArrayList<BlockSection> blockSections,
                                                               HashMap<String, ArrayList<Edge>> nodeMap) {
        var trainDetectorsMap = new HashMap<String, RJSTrainDetector>();
        // Parse the tde from the realised contact in the rsl file
        for (var node : document.selectNodes("/line/nodes/node")) {
            var releaseContactNode = (Element) node;
            var id = releaseContactNode.attributeValue("nodeID");
            var isReleaseContact = Boolean.parseBoolean(releaseContactNode.attributeValue("releaseContact"));
            if (isReleaseContact == true) {
                var trainDetector = new RJSTrainDetector("tde_" + id, ApplicableDirection.BOTH, 0);
                trainDetectorsMap.put(id, trainDetector);
                // link tracks sections back to release contact
                for (var edge : nodeMap.get(id)) {
                    var endReleasePoint = findEndpoint(edge, id);
                    if (endReleasePoint.endpoint == EdgeEndpoint.BEGIN) {
                        edge.routeWaypoints.add(trainDetector);
                        break;
                    }
                }
            }
        }
        // Create the tde at the begin and the end of the BS
        for (var blockSection : blockSections) {
            int numberOfNodes = blockSection.getnodesId().length;
            var beginNodeId = blockSection.getnodesId()[0];
            var endNodeId = blockSection.getnodesId()[numberOfNodes - 1];

            // check if I need to create a detector for the block section begin
            if (trainDetectorsMap.get(beginNodeId) == null)
                tdeCreate(nodeMap, trainDetectorsMap, beginNodeId);

            // check if I need to create a detector for the block section end
            if (trainDetectorsMap.get(endNodeId) == null)
                tdeCreate(nodeMap, trainDetectorsMap, endNodeId);
        }
        return trainDetectorsMap;
    }

    private static ArrayList<RJSTrackSection> findTrackSectionsFromNodeList(String[] nodesID,
                                                                            HashMap<String, ArrayList<Edge>> nodeMap) {
        var trackSections = new ArrayList<RJSTrackSection>();
        for (int i = 0; i < nodesID.length - 1; i++) {
            var thisNodeID = nodesID[i];
            var nextNodeID = nodesID[i + 1];
            var edge = findEdge(thisNodeID, nextNodeID, nodeMap);
            trackSections.add(edge);
        }
        return trackSections;
    }

    private static Edge findEdge(String thisNodeID,
                                 String nextNodeID,
                                 HashMap<String, ArrayList<Edge>> nodeMap) {
        Edge foundEdge = null;
        var edgesFromNode = nodeMap.get(thisNodeID);
        for (var edge : edgesFromNode) {
            if (!edge.getEndNodeID().equals(nextNodeID) && !edge.getStartNodeID().equals(nextNodeID))
                continue;
            foundEdge = edge;
        }
        return foundEdge;
    }

    /**
     * Create the tde and link to the corresponding track sections
     * */
    private static void tdeCreate(HashMap<String, ArrayList<Edge>> nodeMap,
                                  HashMap<String, RJSTrainDetector> trainDetectorsMap,
                                  String nodeId) {

        // Find the position of beginNode in the edge it is in
        for (var edge : nodeMap.get(nodeId)) {
            var endTdePoint = findEndpoint(edge, nodeId);
            RJSTrainDetector trainDetector;
            if (endTdePoint.endpoint == EdgeEndpoint.BEGIN) {
                trainDetector = new RJSTrainDetector("tde_" + nodeId, ApplicableDirection.BOTH, 0);
                // link tracks sections back to train detector
                edge.routeWaypoints.add(trainDetector);
            } else {
                trainDetector = new RJSTrainDetector("tde_" + nodeId, ApplicableDirection.BOTH, edge.length);
            }
            trainDetectorsMap.put(nodeId, trainDetector);
        }
    }

    /**
     * Create the speed sections for normal and reverse direction and add to the corresponding track section
     * */
    private static void speedSectionParse(ArrayList<RJSSpeedSection> rjsSpeedSections,
                                          HashMap<String, RJSTrackSection> rjsTrackSections,
                                          Edge edge,
                                          ArrayList<String> speedIndicators) {
        var ssID = "speed_section_" + edge.id;
        var isSignalised = checkIfIsSignalised(speedIndicators, edge.getStartNodeID());
        var speedSection = new RJSSpeedSection(ssID, isSignalised, edge.getSpeed());
        var speedSectionPart = new RJSSpeedSectionPart(
                ID.from(speedSection), ApplicableDirection.NORMAL, 0, edge.length);
        addSpeedSection(rjsSpeedSections, rjsTrackSections.get(edge.id), speedSection, speedSectionPart);
        // speed limit in the opposite direction
        var ssReverseID = "speed_section_" + edge.id + "_reverse";
        isSignalised = checkIfIsSignalised(speedIndicators, edge.getEndNodeID());
        var speedSectionReverse = new RJSSpeedSection(ssReverseID, isSignalised, edge.getSpeedReverse());
        var speedSectionPartReverse = new RJSSpeedSectionPart(ID.from(speedSectionReverse),
                ApplicableDirection.REVERSE, 0, edge.length);
        addSpeedSection(rjsSpeedSections, rjsTrackSections.get(edge.id), speedSectionReverse, speedSectionPartReverse);
    }

    private static boolean checkIfIsSignalised(ArrayList<String> speedIndicators, String edgeNode) {
        boolean isSignalised = false;
        for (var sI : speedIndicators) {
            if (sI.equals(edgeNode))
                isSignalised = true;
        }
        return isSignalised;
    }

    /**
     * Add the speed section to a list and to the corresponding track section
     * */
    private static void addSpeedSection(ArrayList<RJSSpeedSection> rjsSpeedSections,
                                        RJSTrackSection rjsTrackSection,
             RJSSpeedSection speedSection, RJSSpeedSectionPart speedSectionPart) {
        rjsSpeedSections.add(speedSection);
        rjsTrackSection.speedSections.add(speedSectionPart);
    }

    /**
     * Create the track section link and add to a map
     * */
    private static void addTrackSectionLinks(Edge edge, Edge edge1, String nodeID,
                                             HashMap<String, RJSTrackSectionLink> rjsTrackSectionLinks) {
        var firstTrack = edge;
        var secondTrack = edge1;
        var endPoint = findEndpoint(edge, nodeID);
        if (endPoint.endpoint.equals(EdgeEndpoint.BEGIN)) {
            firstTrack = edge1;
            secondTrack = edge;
        }
        var id = String.join("-", firstTrack.getID(), secondTrack.getID());
        var navigability = findLinkNavigability(firstTrack, secondTrack);
        rjsTrackSectionLinks.put(id,
                new RJSTrackSectionLink(navigability, firstTrack.endEndpoint(), secondTrack.beginEndpoint()));
    }

    /**
     * Read the time points from a Rsl file
     * @return the operational points list for RJS
     */
    private static ArrayList<RJSOperationalPoint> timingPointsParse(Document document,
                                                                     HashMap<String, ArrayList<Edge>> nodeMap) {
        var operationalPoints = new ArrayList<RJSOperationalPoint>();
        for (var node : document.selectNodes("/line/nodes/track")) {
            var trackNode = (Element) node;
            // create the operational point
            var type = trackNode.attributeValue("type");
            if (!type.equals("timingPoint") && !type.equals("stopBoardPass"))
                continue;

            var id = trackNode.attributeValue("nodeID");
            var rjsOperationalPoint = new RJSOperationalPoint(id);
            operationalPoints.add(rjsOperationalPoint);

            // link tracks sections back to the operational point
            for (var edge : nodeMap.get(id)) {
                var endOpPoint = findEndpoint(edge, id);
                if (endOpPoint.endpoint == EdgeEndpoint.BEGIN) {
                    var opPart = new RJSOperationalPointPart(ID.from(rjsOperationalPoint), 0, 0);
                    edge.operationalPoints.add(opPart);
                    break;
                }
            }
        }
        return operationalPoints;
    }

    /**
     * Read the switches from a Rsl file
     * @return the switches list for RJS
     */
    private static ArrayList<RJSSwitch> switchParse(Document document, HashMap<String, ArrayList<Edge>> nodeMap,
             HashMap<String, RJSTrackSectionLink> trackSectionLinks) {
        var switches = new ArrayList<RJSSwitch>();

        for (var node : document.selectNodes("/line/nodes/switch")) {
            var switchNode = (Element) node;
            var id = switchNode.attributeValue("nodeID");
            var baseBranchNodeID = switchNode.attributeValue("start");
            var baseTrackSection = findBase(nodeMap, id, baseBranchNodeID);
            var otherTrackSections = findOthers(nodeMap, id, baseTrackSection);
            var base = findEndpoint(baseTrackSection, id);
            var left = findEndpoint(otherTrackSections.get(0), id);
            var right = findEndpoint(otherTrackSections.get(1), id);
            var rjsSwitch = new RJSSwitch(id, base, left, right, 0);
            switches.add(rjsSwitch);

            //create 2 track section links for each switch: base/right, base/left
            addTrackSectionLinks(baseTrackSection, otherTrackSections.get(0), id, trackSectionLinks);
            addTrackSectionLinks(baseTrackSection, otherTrackSections.get(1), id, trackSectionLinks);
        }
        return switches;
    }

    /**
     * Check if one of the tracks sections is not bidirectional
     * @return the navigability of the link
     */
    private static ApplicableDirection findLinkNavigability(Edge section, Edge section1) {
        ApplicableDirection navigability = ApplicableDirection.BOTH;
        if ((!section.isBidirectional()) || (!section1.isBidirectional()))
            navigability = ApplicableDirection.NORMAL;
        return navigability;
    }

    /**
     * Find the two tracks not being the base track of the switch
     */
    private static ArrayList<Edge> findOthers(HashMap<String,
            ArrayList<Edge>> nodeMap, String id, Edge baseTrackSection) {
        ArrayList<Edge> others = new ArrayList<>();
        for (var edge : nodeMap.get(id)) {
            if (!edge.equals(baseTrackSection))
                others.add(edge);
        }
        return others;
    }

    /**
     * Find the base track of the switch
     */
    private static Edge findBase(HashMap<String, ArrayList<Edge>> nodeMap, String id, String baseBranchNodeID) {
        Edge baseEdge = null;
        for (var edge : nodeMap.get(id)) {
            if (edge.getEndNodeID().equals(baseBranchNodeID)
                    || edge.getStartNodeID().equals(baseBranchNodeID)) {
                baseEdge = edge;
                break;
            }
        }
        return baseEdge;
    }

    /**
     * Find the EndPoint of the track section corresponding to a node
     */
    private static RJSTrackSection.EndpointID findEndpoint(Edge trackSection, String nodeID) {
        if (trackSection.getStartNodeID().equals(nodeID))
            return trackSection.beginEndpoint();
        return trackSection.endEndpoint();
    }

    /**
     * Put the edge twice in the nodeMap with startNodeID and endNodeID as keys
     */
    private static void createNodeMap(HashMap<String, ArrayList<Edge>> nodeMap, Edge edge) {
        var startNodeID = edge.getStartNodeID();
        var endNodeID = edge.getEndNodeID();

        for (var node : new String[]{ startNodeID, endNodeID }) {
            var neighbors = nodeMap.get(node);
            if (neighbors == null) {
                var relatedTrackSections = new ArrayList<Edge>();
                relatedTrackSections.add(edge);
                nodeMap.put(node, relatedTrackSections);
            } else {
                neighbors.add(edge);
            }
        }
    }
}


