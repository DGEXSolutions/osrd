package fr.sncf.osrd.railjson.schema.infra;

import com.squareup.moshi.Json;
import fr.sncf.osrd.railjson.schema.common.ID;
import fr.sncf.osrd.railjson.schema.common.Identified;
import fr.sncf.osrd.railjson.schema.infra.trackobjects.RJSRouteWaypoint;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class RJSRoute implements Identified {
    public String id;

    /** List of the switches and their position through which the route transits */
    @Json(name = "switches_position")
    public Map<ID<RJSSwitch>, RJSSwitch.Position> switchesPosition;

    @Json(name = "release_groups")
    public List<Set<ID<RJSTVDSection>>> releaseGroups;

    /** Waypoint placed just before the route, either a buffer stop or a detector attached to a signal */
    @Json(name = "entry_point")
    public ID<RJSRouteWaypoint> entryPoint;

    /** Routes are described as a list of TVD Sections, Switches in specific positions, and an entry point */
    public RJSRoute(
            String id,
            Map<ID<RJSSwitch>, RJSSwitch.Position> switchesPosition,
            List<Set<ID<RJSTVDSection>>> releaseGroups,
            ID<RJSRouteWaypoint> entryPoint
    ) {
        this.id = id;
        this.switchesPosition = switchesPosition;
        this.releaseGroups = releaseGroups;
        this.entryPoint = entryPoint;
    }

    @Override
    public String getID() {
        return id;
    }

    public enum State {
        FREE,
        REQUESTED,
        RESERVED,
        OCCUPIED,
        CBTC_REQUESTED,
        CBTC_RESERVED,
        CBTC_OCCUPIED,
        CONFLICT
    }
}
