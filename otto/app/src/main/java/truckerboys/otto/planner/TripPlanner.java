package truckerboys.otto.planner;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;

import org.joda.time.Duration;

import java.util.ArrayList;
import java.util.LinkedList;

import truckerboys.otto.directionsAPI.IDirections;
import truckerboys.otto.directionsAPI.Route;
import truckerboys.otto.driver.User;
import truckerboys.otto.placesAPI.IPlaces;
import truckerboys.otto.utils.eventhandler.EventTruck;
import truckerboys.otto.utils.eventhandler.events.ChangedRouteEvent;
import truckerboys.otto.utils.exceptions.InvalidRequestException;
import truckerboys.otto.utils.exceptions.NoConnectionException;
import truckerboys.otto.utils.positions.MapLocation;
import truckerboys.otto.utils.positions.RestLocation;

public class
        TripPlanner {
    private final Duration MARGINAL = Duration.standardMinutes(10);
    private User user;
    private IRegulationHandler regulationHandler;
    private IDirections directionsProvider;
    private IPlaces placesProvider;


    //Route preferences
    private Route activeRoute;
    private MapLocation startLocation;
    private MapLocation finalDestination;
    private MapLocation[] checkpoints;

    private MapLocation chosenStop;

    private int nbrOfDirCalls = 0;

    public TripPlanner(IRegulationHandler regulationHandler, IDirections directionsProvider, IPlaces placesProvider, User user) {
        this.regulationHandler = regulationHandler;
        this.directionsProvider = directionsProvider;
        this.placesProvider = placesProvider;
        this.user = user;
    }

    /**
     * Get an updated version of the route
     *
     * @param currentLocation Location of the device.
     * @return an updated route.
     * @throws InvalidRequestException
     * @throws NoConnectionException
     */
    public void updateRoute(MapLocation currentLocation) throws InvalidRequestException, NoConnectionException {
        //TODO also set ETA to alternative route but all this after getRoute
        if (chosenStop != null) {
            chosenStop.setEta(directionsProvider.getETA(currentLocation, chosenStop));
        }
        activeRoute = getCalculatedRoute();
        EventTruck.getInstance().newEvent(new ChangedRouteEvent());
    }

    /**
     * Get the active route
     *
     * @return the active route
     */
    public Route getRoute() {
        return activeRoute;
    }

    /**
     * Returns a route to the same destination with chosen stop added to map
     *
     * @param chosenStop Where the driver wants to take  a break.
     * @return Optimum route for the given preferences
     * @throws InvalidRequestException
     * @throws NoConnectionException
     */
    public void updateChoosenStop(MapLocation chosenStop) throws InvalidRequestException, NoConnectionException {
        this.chosenStop = chosenStop;
        activeRoute = getCalculatedRoute();
        EventTruck.getInstance().newEvent(new ChangedRouteEvent());
    }

    /**
     * Calculate a new route based on a start location and end location provided.
     *
     * @param startLocation    The location that the route should start from.
     * @param finalDestination The location that the route should end at.
     * @param checkpoints      Checkpoints to visit before the end location
     * @return optimized route with POIs along the route (null if connection to Google is not established)
     * @throws InvalidRequestException
     * @throws NoConnectionException
     */
    public void setNewRoute(MapLocation startLocation, MapLocation finalDestination, MapLocation... checkpoints) throws InvalidRequestException, NoConnectionException {
        //TODO Remove hard coding
        this.startLocation = new MapLocation(new LatLng(57.6879752, 11.9797901));
        this.finalDestination = finalDestination;
        this.checkpoints = checkpoints;
        this.chosenStop = null;
        updateRoute(startLocation);
    }

    /**
     * Calculates a new route and uses the instance variables to decide what route,
     * and should also write to those variables.
     *
     * @return a route.
     * @throws NoConnectionException
     * @throws InvalidRequestException
     */
    private Route getCalculatedRoute() throws NoConnectionException, InvalidRequestException {
        Route optimalRoute = null;
        Duration sessionTimeLeft = regulationHandler.getThisSessionTL(user.getHistory()).getTimeLeft();

        Route directRoute = directionsProvider.getRoute(startLocation, finalDestination, checkpoints);
        nbrOfDirCalls++;

        //TODO Implement check if gas is enough for this session

        System.out.println("timeLeft: " + regulationHandler.getThisDayTL(user.getHistory()).getTimeLeft().getMillis());
        System.out.println("timeLeft Extended: " + regulationHandler.getThisDayTL(user.getHistory()).getExtendedTimeLeft().getMillis());

        //Returns the direct route if ETA is shorter than the time you have left to drive
        if (directRoute.getEtaToFirstCheckpoint().isShorterThan(sessionTimeLeft)) {
            optimalRoute = directRoute;
            optimalRoute.setAlternativeStops(calculateAlternativeStops(directRoute.getEtaToFirstCheckpoint().dividedBy(2), directRoute.getEta().dividedBy(4)));
        }

        //If there is no time left on this session
        else if (sessionTimeLeft.isEqual(Duration.ZERO)) {
            //TODO implement finding closest stop in the right direction
        }

        //If the location is within reach this day but not this session
        else if (!directRoute.getEtaToFirstCheckpoint().isShorterThan(sessionTimeLeft) &&
                directRoute.getEtaToFirstCheckpoint().isShorterThan(regulationHandler.getThisDayTL(user.getHistory()).getTimeLeft())) {

            //If the ETA/2 is longer than time left on session
            if (directRoute.getEtaToFirstCheckpoint().dividedBy(2).isLongerThan(sessionTimeLeft)) {
                optimalRoute = getOptimizedRoute(directRoute, regulationHandler.getThisSessionTL(user.getHistory()).getTimeLeft().dividedBy(2));
            } else {
                optimalRoute = getOptimizedRoute(directRoute, regulationHandler.getThisDayTL(user.getHistory()).getTimeLeft().dividedBy(2));
            }
            optimalRoute.setAlternativeStops(calculateAlternativeStops(sessionTimeLeft, sessionTimeLeft.dividedBy(2)));
        }

        //If the location is not within reach this day (drive maximum distance)
        else if (!directRoute.getEtaToFirstCheckpoint().isShorterThan(regulationHandler.getThisDayTL(user.getHistory()).getTimeLeft())) {
            optimalRoute = getOptimizedRoute(directRoute, regulationHandler.getThisDayTL(user.getHistory()).getTimeLeft().minus(MARGINAL));
        }
        return optimalRoute;
    }

    /**
     * Get a list of one stop location close to each wanted ETA.
     *
     * @param stopsETA times that stops are wanted in.
     * @return list if stop locations
     */
    private ArrayList<MapLocation> calculateAlternativeStops(Duration... stopsETA) {
        //TODO Implement this stuff
        return null;
    }


    /**
     * Get optimized route with one rest location as a checkpoint.
     *
     * @param directRoute Route from Google Directions without any rest or gas stops.
     * @param within      Within what time a rest should be made.
     * @return An optimized route with the most suitable rest location as a checkpoint.
     */
    private Route getOptimizedRoute(Route directRoute, Duration within) throws InvalidRequestException, NoConnectionException {
        Route optimalRoute = null;
        LatLng optimalLatLong;
        ArrayList<MapLocation> closeLocations;

        /*
        while (closeLocations.size() == 0) {
            optimalLatLong = findLatLngWithinDuration(directRoute, within);
            closeLocations = placesProvider.getNearbyRestLocations(optimalLatLong);
            Log.w("NBRofCloseLocations", closeLocations.size() + "");

            //If no place is found, search for ten minutes before
            within = within.minus(Duration.standardMinutes(10));
        }
        */

        optimalLatLong = findLatLngWithinDuration(directRoute, within);
        closeLocations = placesProvider.getNearbyRestLocations(optimalLatLong);
        Log.w("NBRofCloseLocations", closeLocations.size() + "");

        if (closeLocations.size() == 0) {
            MapLocation forcedLocation = new MapLocation(optimalLatLong);
            forcedLocation.setAddress("Forced location");
            closeLocations.add(forcedLocation);
        }

        //Just calculating the five best matches from Google
        for (int i = 0; i < 5 && i < closeLocations.size(); i++) {
            //Temporary creation
            LinkedList<MapLocation> tempList = new LinkedList<MapLocation>();

            if (checkpoints != null) {
                for (MapLocation location : checkpoints) {
                    tempList.add(location);
                }
            }
            tempList.add(new MapLocation(closeLocations.get(i)));

            //Checks if the restLocation is a possible stop and is faster than the previous
            Route temp = directionsProvider.getRoute(startLocation, finalDestination, tempList.toArray(new MapLocation[tempList.size()]));

            //TODO Make this look better
            if (temp.getCheckpoints().get(0).getEta().isShorterThan(regulationHandler.getThisSessionTL(user.getHistory()).getTimeLeft())) {
                if (optimalRoute == null) {
                    this.chosenStop = closeLocations.get(i);
                    optimalRoute = temp;
                } else if (temp.getEta().isShorterThan(optimalRoute.getEta())) {
                    this.chosenStop = closeLocations.get(i);
                    optimalRoute = temp;
                }
            }
        }
        return optimalRoute;
    }

    /**
     * Find the coordinate on the polyline that have the smallest delta value from the time left.
     *
     * @param directRoute Route from Google Directions without any rest or gas stops.
     * @param timeLeft    ETA that the coordinate should be close to.
     * @return The coordinate that matches time left the best.
     */
    private LatLng findLatLngWithinDuration(Route directRoute, Duration timeLeft) throws InvalidRequestException, NoConnectionException {
        ArrayList<LatLng> coordinates = directRoute.getOverviewPolyline();

        Log.w("PolylineSize", coordinates.size() + "");
        int topIndex = coordinates.size() - 1;
        int bottomIndex = 0;
        int currentIndex = (topIndex + bottomIndex) / 2;

        Duration etaToCoordinate = directionsProvider.getETA(new MapLocation(directRoute.getOverviewPolyline().get(0)),
                new MapLocation(coordinates.get(currentIndex)));
        nbrOfDirCalls++;


        while (etaToCoordinate.isShorterThan(timeLeft.minus(Duration.standardMinutes(5))) ||
                etaToCoordinate.isLongerThan(timeLeft.plus(Duration.standardMinutes(5)))) {
            if (etaToCoordinate.isLongerThan(timeLeft)) {
                topIndex = currentIndex;
            } else {
                bottomIndex = currentIndex;
            }

            currentIndex = (topIndex + bottomIndex) / 2;
            //Just to be safe
            if (topIndex - bottomIndex < 2) {
                break;
            }
            Log.w("findLatLng", "topIndex: " + topIndex);
            Log.w("findLatLng", "bottomIndex: " + bottomIndex);
            etaToCoordinate = directionsProvider.getETA(new MapLocation(directRoute.getOverviewPolyline().get(0)),
                    new MapLocation(coordinates.get(currentIndex)));
            nbrOfDirCalls++;
            Log.w("nbrOfDirCalls", nbrOfDirCalls + "");

        }
        Log.w("nbrOfDirCalls", nbrOfDirCalls + "");
        return coordinates.get(currentIndex);
    }

    /**
     * Checks if a MapLocation already exists in an ArrayList
     *
     * @param location Location that you want to see if the list contains
     * @param list     List that might contain given locataion
     * @return True if list contains a location with the same coordinates as the location
     */
    private boolean locationExistsInList(MapLocation location, ArrayList<? extends
            MapLocation> list) {
        for (MapLocation temp : list) {
            if (temp.getLatitude() == location.getLatitude() && temp.getLongitude() == location.getLongitude()) {
                return true;
            }
        }
        return false;
    }
}