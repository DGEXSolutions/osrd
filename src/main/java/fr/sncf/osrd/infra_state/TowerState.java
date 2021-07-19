package fr.sncf.osrd.infra_state;

import fr.sncf.osrd.infra.SuccessionTable;
import fr.sncf.osrd.infra.TVDSection;
import fr.sncf.osrd.train.Train;
import fr.sncf.osrd.simulation.Simulation;
import fr.sncf.osrd.simulation.SimulationError;
import fr.sncf.osrd.infra.Infra;

import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;

public class TowerState {

    public final List<SuccessionTable> initTables;
    // map switchID -> SwitchState
    private HashMap<String, State> state;
    // map tvdSectionId -> HashSet<Request>
    private HashMap<String, HashSet<Request>> waitingList;
    // map trainID -> routeID
    private HashMap<String, String> lastRequestedRoute;

    /**
     * Create a switch post without theoric trains successions tables
     * @param infra the given infrastructure
     * @return a TowerState with an empty trains successions table for each switch
     */
    public static TowerState makeTowerStateWithoutTables(Infra infra) {
        var initTables = new ArrayList<SuccessionTable>();
        for (var s : infra.switches) {
            initTables.add(new SuccessionTable(s.id, new ArrayList<>()));
        }
        return makeTowerState(infra, initTables);
    }

    /**
     * Create a switch post with given theoretic trains successions tables
     * @param infra the given infrastructure
     * @param initTables the given trains succession tables, exactly one per switch
     * @return a TowerState with the given trains succession tables
     */
    public static TowerState makeTowerState(Infra infra, List<SuccessionTable> initTables) {
        
        var state = new HashMap<String, State>();
        var waitingList = new HashMap<String, HashSet<Request>>();
        var lastRequestedRoute = new HashMap<String, String>();

        for (var table : initTables) {
            var q = new State(new SuccessionTable(table), 0, null, new HashMap<>());
            state.put(table.switchID, q);
        }

        // Create default states for unspecified switches
        for (var s : infra.switches) {
            if (!state.containsKey(s.id)) {
                state.put(s.id, new State(new SuccessionTable(s.id, new ArrayList<>()),
                        0, null, new HashMap<>()));
            }
        }

        var sps = new TowerState(initTables, state, waitingList, lastRequestedRoute);

        for (var table : initTables) {
            for (var trainID : table.table) {
                sps.plan(table.switchID, trainID);
            }
        }

        for (var tvdID : infra.tvdSections.keySet())
            waitingList.put(tvdID, new HashSet<>());
        return sps;
    }

    private TowerState(
            List<SuccessionTable> initTables,
            HashMap<String, State> state,
            HashMap<String, HashSet<Request>> waitingList,
            HashMap<String, String> lastRequestedRoute
    ) {
        this.initTables = initTables;
        this.state = state;
        this.waitingList = waitingList;
        this.lastRequestedRoute = lastRequestedRoute;
    }

    /**
     * check if a switch is set for a train
     * @param switchID the identifier of the switch
     * @param trainID the identifier of the train
     * @return true iff the given switch is set for the given train
     */
    public boolean isCurrentAllowed(String switchID, String trainID) {
        assert state.containsKey(switchID);
        var q = state.get(switchID);
        return trainID.equals(q.currentTrainAllowed);
    }

    private boolean isPlanned(String switchID, String trainID) {
        assert state.containsKey(switchID);
        var q = state.get(switchID);
        return q.trainCount.getOrDefault(trainID, 0) > 0;
    }

    private boolean isNext(String switchID, String trainID) {
        assert state.containsKey(switchID);
        var q = state.get(switchID);
        return q.table.get(q.currentIndex).equals(trainID);
    }

    private void plan(String switchID, String trainID) {
        assert state.containsKey(switchID);
        var q = state.get(switchID);
        q.table.add(trainID);
        var count = q.trainCount.getOrDefault(trainID, 0);
        q.trainCount.put(trainID, count + 1);
    }

    private void next(String switchID) {
        assert state.containsKey(switchID);
        var q = state.get(switchID);
        var trainID = q.table.get(q.currentIndex);
        var count = q.trainCount.get(trainID);
        q.trainCount.put(trainID, count - 1);
        q.currentIndex++;
    }

    private void process(Simulation sim, Request request) throws SimulationError {

        var trainID = request.train.schedule.trainID;

        // check if the route is free
        for (var tvdSectionPath : request.routeState.route.tvdSectionsPaths) {
            var tvdSectionIndex = tvdSectionPath.tvdSection.index;
            if (sim.infraState.getTvdSectionState(tvdSectionIndex).isReserved()) {
                System.out.println("TOWER NOT FREE : " + request.train.getName() + " : " + request.routeState.route.id);
                return;
            }
        }
        // check if the train is next on each switch of the route
        for (var s : request.routeState.route.switchesPosition.keySet()) {
            if (!isPlanned(s.id, trainID)) { // plan the train if not planned
                plan(s.id, trainID);
            }
            if (!isNext(s.id, trainID)) { // check if next
                System.out.println("TOWER NOT NEXT : " + request.train.getName() + " : " + request.routeState.route.id);
                return;
            }
        }

        // erase the request of the waiting list of each tvd section of the route
        for (var tvdSectionPath : request.routeState.route.tvdSectionsPaths) {
            waitingList.get(tvdSectionPath.tvdSection.id).remove(request);
        }

        // go to next train to each switch of the route
        for (var s : request.routeState.route.switchesPosition.keySet()) {
            next(s.id);
            state.get(s.id).currentTrainAllowed = request.train.getName();
        }

        // reserve the route
        System.out.println("TOWER ACCEPTED : " + request.train.getName() + " : " + request.routeState.route.id);
        request.routeState.reserve(sim);
    }

    /**
     * enqueue a route reservation request in the waiting lists of the TowerState
     * @param sim the infra
     * @param routeState the routeState of the request
     * @param train the train that emited the request
     * @throws SimulationError thrown when an error happens
     */
    public void request(Simulation sim, RouteState routeState, Train train) throws SimulationError {
        var trainID = train.schedule.trainID;
        if (!lastRequestedRoute.containsKey(trainID) || !lastRequestedRoute.get(trainID).equals(routeState.route.id)) {
            lastRequestedRoute.put(trainID, routeState.route.id);

            System.out.println("TOWER REQUEST : " + train.getName() + " : " + routeState.route.id);

            var request = new Request(train, routeState);
            for (var tvdSectionPath : routeState.route.tvdSectionsPaths) {
                var tvdSectionID = tvdSectionPath.tvdSection.id;
                waitingList.get(tvdSectionID).add(request);
            }
            process(sim, request);
        }
    }

    /**
     * notify the towerState that a TVDSection is released and that he can try to process some enqueued requests
     * @param sim the simulation
     * @param tvdSection the released TVDSection
     * @throws SimulationError thrown when an error happens
     */
    public void notifyFreed(Simulation sim, TVDSection tvdSection) throws SimulationError {
        var list = new ArrayList<>(waitingList.get(tvdSection.id));
        for (var request : list) {
            process(sim, request);
        }
    }

    private static class Request {
        public Train train;
        public RouteState routeState;

        public Request(Train train, RouteState routeState) {
            this.train = train;
            this.routeState = routeState;
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof Request)) {
                return false;
            }
            var request = (Request) object;
            return train.schedule.trainID.equals(request.train.schedule.trainID)
                    && routeState.route.id.equals(request.routeState.route.id);
        }

        @Override
        public int hashCode() {
            return toString().hashCode();
        }

        @Override
        public String toString() {
            return train.schedule.trainID + "#" + routeState.route.id;
        }
    }

    private static class State {
        
        SuccessionTable table;
        int currentIndex;
        String currentTrainAllowed;
        HashMap<String, Integer> trainCount;

        public State(
                SuccessionTable table,
                int currentIndex,
                String currentTrainAllowed,
                HashMap<String, Integer> trainCount
        ) {
            this.table = table;
            this.currentIndex = currentIndex;
            this.currentTrainAllowed = currentTrainAllowed;
            this.trainCount = trainCount;
        }
    }
}
