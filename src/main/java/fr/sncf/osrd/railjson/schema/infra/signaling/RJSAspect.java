package fr.sncf.osrd.railjson.schema.infra.signaling;

import fr.sncf.osrd.railjson.schema.common.Identified;

public class RJSAspect implements Identified {
    public final String id;

    /** An HTML color */
    public final String color;

    public RJSAspect(String id, String color) {
        this.id = id;
        this.color = color;
    }

    @Override
    public String getID() {
        return id;
    }
}