package fr.sncf.osrd.railjson.schema.infra;

import com.squareup.moshi.Json;
import fr.sncf.osrd.railjson.schema.common.ID;
import fr.sncf.osrd.railjson.schema.common.Identified;
import fr.sncf.osrd.railjson.schema.infra.trackobjects.RJSRouteWaypoint;
import fr.sncf.osrd.railjson.schema.infra.trackobjects.RJSSignal;
import fr.sncf.osrd.railjson.schema.infra.trackranges.RJSOperationalPointPart;
import fr.sncf.osrd.railjson.schema.infra.trackranges.RJSSpeedSectionPart;
import fr.sncf.osrd.railjson.schema.infra.trackranges.RJSCatenarySectionPart;
import fr.sncf.osrd.utils.graph.EdgeEndpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class RJSTrackSection implements Identified {
    public String id;
    public double length;

    /** List of waypoints (detectors and buffer stops) on the track section */
    @Json(name = "route_waypoints")
    public List<RJSRouteWaypoint> routeWaypoints;

    /** List of signals on the track section */
    public List<RJSSignal> signals;

    /** List of operational points on the track section */
    @Json(name = "operational_points")
    public List<RJSOperationalPointPart> operationalPoints;

    /** List of speed sections on the track section */
    @Json(name = "speed_sections")
    public List<RJSSpeedSectionPart> speedSections;

    /** List of the catenaries on the track section */
    @Json(name = "catenaries")
    public List<RJSCatenarySectionPart> catenaries;

    @Json(name = "endpoints_coords")
    public List<List<Double>> endpointCoords;

    /** Creates a new track section */
    public RJSTrackSection(
            String id,
            double length,
            List<RJSRouteWaypoint> routeWaypoints,
            List<RJSSignal> signals,
            List<RJSOperationalPointPart> operationalPoints,
            List<RJSSpeedSectionPart> speedSections,
            List<RJSCatenarySectionPart> catenaries
    ) {
        this.id = id;
        this.length = length;
        this.routeWaypoints = routeWaypoints;
        this.signals = signals;
        this.operationalPoints = operationalPoints;
        this.speedSections = speedSections;
        this.catenaries = catenaries;
    }

    public RJSTrackSection(
            String id,
            double length
    ) {
        this(id, length, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    @Override
    public String getID() {
        return id;
    }

    public EndpointID beginEndpoint() {
        return new EndpointID(ID.from(this), EdgeEndpoint.BEGIN);
    }

    public EndpointID endEndpoint() {
        return new EndpointID(ID.from(this), EdgeEndpoint.END);
    }

    /** An identifier for a side of a specific track section */
    public static final class EndpointID {
        public ID<RJSTrackSection> section;
        public EdgeEndpoint endpoint;

        public EndpointID(ID<RJSTrackSection> section, EdgeEndpoint endpoint) {
            this.section = section;
            this.endpoint = endpoint;
        }

        @Override
        public int hashCode() {
            return Objects.hash(section, endpoint);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null)
                return false;
            if (obj.getClass() != EndpointID.class)
                return false;
            var o = (EndpointID) obj;
            return section.equals(o.section) && endpoint.equals(o.endpoint);
        }

        @Override
        public String toString() {
            return String.format(
                    "RJSTrackSection.EndpointID { section=%s, endpoint=%s }",
                    section.id, endpoint.toString()
            );
        }
    }
}
