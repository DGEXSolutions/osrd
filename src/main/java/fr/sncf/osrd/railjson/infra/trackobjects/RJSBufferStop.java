package fr.sncf.osrd.railjson.infra.trackobjects;

import fr.sncf.osrd.utils.graph.ApplicableDirections;

public class RJSBufferStop extends RJSRouteWaypoint {
    public RJSBufferStop(String id, ApplicableDirections applicableDirections, double position) {
        super(id, applicableDirections, position);
    }
}