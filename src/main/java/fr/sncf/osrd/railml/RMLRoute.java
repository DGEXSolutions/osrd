package fr.sncf.osrd.railml;

import fr.sncf.osrd.infra.InvalidInfraException;
import fr.sncf.osrd.infra.railjson.schema.ID;
import fr.sncf.osrd.infra.railjson.schema.RJSRoute;
import fr.sncf.osrd.infra.railjson.schema.RJSSwitch;
import fr.sncf.osrd.infra.railjson.schema.RJSTVDSection;
import org.dom4j.Document;
import org.dom4j.Element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class RMLRoute {
    static ArrayList<RJSRoute> parse(
            Document document
    ) throws InvalidInfraException {
        var res = new ArrayList<RJSRoute>();
        var xpath = "/railML/interlocking/assetsForIL/routes/route";
        for (var routeNode :  document.selectNodes(xpath)) {
            var route = (Element) routeNode;
            var id = route.attributeValue("id");

            var tvdSections = parseTVDSections(route);
            var switchesPosition = parseSwitchesPosition(route);
            res.add(new RJSRoute(id, tvdSections, switchesPosition, new ArrayList<>()));
        }
        return res;
    }

    private static ArrayList<ID<RJSTVDSection>> parseTVDSections(Element route) {
        var tvdSections = new ArrayList<ID<RJSTVDSection>>();
        for (var tvdSection : route.elements("hasTvdSection")) {
            var tvdSectionID = new ID<RJSTVDSection>(tvdSection.attributeValue("ref"));
            tvdSections.add(tvdSectionID);
        }
        return tvdSections;
    }

    private static Map<ID<RJSSwitch>, RJSSwitch.Position> parseSwitchesPosition(Element route)
            throws InvalidInfraException {
        var switchesPosition = new HashMap<ID<RJSSwitch>, RJSSwitch.Position>();
        for (var switchPosition : route.elements("facingSwitchInPosition")) {
            var switchID = new ID<RJSSwitch>(switchPosition.element("refersToSwitch").attributeValue("ref"));
            var position = switchPosition.attributeValue("inPosition");
            switch (position) {
                case "LEFT":
                    switchesPosition.put(switchID, RJSSwitch.Position.LEFT);
                    break;
                case "RIGHT":
                    switchesPosition.put(switchID, RJSSwitch.Position.RIGHT);
                    break;
                default:
                    throw new InvalidInfraException(
                            String.format("SwitchPosition has an invalid position: '%s'", position));
            }
        }
        return switchesPosition;
    }
}