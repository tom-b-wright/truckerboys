package truckerboys.otto.planner;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;

import org.joda.time.Duration;
import org.joda.time.Instant;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import truckerboys.otto.directionsAPI.IDirections;
import truckerboys.otto.directionsAPI.Route;
import truckerboys.otto.driver.User;
import truckerboys.otto.placesAPI.IPlaces;
import truckerboys.otto.utils.eventhandler.EventBus;
import truckerboys.otto.utils.eventhandler.events.ChangedRouteEvent;
import truckerboys.otto.utils.exceptions.CheckpointNotFoundException;
import truckerboys.otto.utils.exceptions.InvalidRequestException;
import truckerboys.otto.utils.exceptions.NoActiveRouteException;
import truckerboys.otto.utils.exceptions.NoConnectionException;
import truckerboys.otto.utils.positions.MapLocation;
import truckerboys.otto.utils.positions.RouteLocation;
import truckerboys.otto.vehicle.FuelTankInfo;

public class
        TripPlanner {
    private final Duration MARGINAL = Duration.standardMinutes(10);
    private User user;
    private IRegulationHandler regulationHandler;
    private IDirections directionsProvider;
    private IPlaces placesProvider;

    //Route preferences
    private PlannedRoute activeRoute;
    private MapLocation startLocation;
    private MapLocation finalDestination;
    private List<MapLocation> checkpoints;

    private RouteLocation chosenStop;
    private RouteLocation recommendedStop;

    private MapLocation currentLocation;

    private FuelTankInfo fuelTank;

    public TripPlanner(IRegulationHandler regulationHandler, IDirections directionsProvider,
                       IPlaces placesProvider, User user, FuelTankInfo fuelTank) {
        this.regulationHandler = regulationHandler;
        this.directionsProvider = directionsProvider;
        this.placesProvider = placesProvider;
        this.user = user;
        this.fuelTank = fuelTank;
    }

    /**
     * Get an updated version of the route
     *
     * @param currentLocation Location of the device.
     * @throws InvalidRequestException
     * @throws NoConnectionException
     */
    public void updateRoute(MapLocation currentLocation) throws InvalidRequestException, NoConnectionException {
        this.currentLocation = currentLocation;
        activeRoute = getCalculatedRoute();
        EventBus.getInstance().newEvent(new ChangedRouteEvent());
    }

    /**
     * Get the active route
     *
     * @return the active route
     * @throws NoActiveRouteException
     */
    public PlannedRoute getRoute() throws NoActiveRouteException {
        if (activeRoute == null) {
            throw new NoActiveRouteException("There is no active route");
        }
        //Making it thread secure
        return new PlannedRoute(activeRoute);
    }

    /**
     * Returns a route to the same destination with chosen stop added to map
     *
     * @param chosenStop Where the driver wants to take  a break.
     * @throws InvalidRequestException
     * @throws NoConnectionException
     */
    public void setChoosenStop(RouteLocation chosenStop) throws InvalidRequestException, NoConnectionException {
        this.chosenStop = chosenStop;
        activeRoute = getCalculatedRoute();
        EventBus.getInstance().newEvent(new ChangedRouteEvent());
    }

    /**
     * Removes checkpoint from the route, and finishes the route if the checkpoint is the final destination.
     *
     * @param passedCheckpoint checkpoint that has been passed.
     * @throws CheckpointNotFoundException
     */
    public void passedCheckpoint(MapLocation passedCheckpoint) throws CheckpointNotFoundException {
        boolean checkpointFound = false;

        if (chosenStop != null && passedCheckpoint.equalCoordinates(chosenStop)) {
            chosenStop = null;
            checkpointFound = true;
        }

        if (recommendedStop!= null && passedCheckpoint.equalCoordinates(recommendedStop)) {
            recommendedStop = null;
            checkpointFound = true;
        }

        if (finalDestination != null && passedCheckpoint.equalCoordinates(finalDestination)) {
            activeRoute = null;
            checkpointFound = true;
        }
        if (checkpoints != null) {
            ArrayList<MapLocation> temp = new ArrayList<MapLocation>();
            for (MapLocation checkpoint : checkpoints) {
                if (passedCheckpoint.equalCoordinates(checkpoint)) {
                    checkpointFound = true;
                } else {
                    temp.add(checkpoint);
                }
            }
            checkpoints = temp;
        }

        if (!checkpointFound) {
            throw new CheckpointNotFoundException("does not exist");
        }
    }

    /**
     * Calculate a new route based on a start location and end location provided.
     *
     * @param startLocation    The location that the route should start from.
     * @param finalDestination The location that the route should end at.
     * @param checkpoints      Checkpoints to visit before the end location
     * @throws InvalidRequestException
     * @throws NoConnectionException
     */
    public void setNewRoute(MapLocation startLocation, MapLocation finalDestination, List<MapLocation> checkpoints) throws InvalidRequestException, NoConnectionException {
        this.startLocation = startLocation;
        this.finalDestination = finalDestination;
        this.checkpoints = checkpoints;
        this.chosenStop = null;
        this.currentLocation = startLocation;
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
    private PlannedRoute getCalculatedRoute() throws NoConnectionException, InvalidRequestException {
        Route optimalRoute;
        Duration sessionTimeLeft = regulationHandler.getThisSessionTL(user.getHistory()).getTimeLeft();
        ArrayList<RouteLocation> alternativeLocations = new ArrayList<RouteLocation>();
        RouteLocation displayedRecommended;

        Route directRoute = directionsProvider.getRoute(currentLocation, finalDestination, checkpoints);

        //If the truck does not have enough fuel to get to the first checkpoint
        boolean gasStationNeeded = fuelTank.getMileage() * 1000 < directRoute.getCheckpoints().get(0).getDistance();

        //TODO Implement check if gas is enough for this session
        if (chosenStop != null) {
            //Setting the recommended as it will be in alternative stops
            Route calculationRoute = getOptimizedRoute(directRoute, Duration.standardMinutes(5), gasStationNeeded);
            if (calculationRoute.getCheckpoints().size() > 0) {
                alternativeLocations.add(calculationRoute.getCheckpoints().get(0));
            }

            ArrayList<MapLocation> tempCheckpoints = new ArrayList<MapLocation>();
            tempCheckpoints.add(chosenStop);
            if (checkpoints != null) {
                tempCheckpoints.addAll(checkpoints);
            }
            displayedRecommended = chosenStop;

            optimalRoute = directionsProvider.getRoute(currentLocation, finalDestination, tempCheckpoints);

            alternativeLocations.addAll(calculateAlternativeStops(directRoute, gasStationNeeded,
                    directRoute.getCheckpoints().get(0).getEta().dividedBy(2),
                    directRoute.getCheckpoints().get(0).getEta().dividedBy(3)));
        } else {

            //Returns the direct route if ETA is shorter than the time you have left to drive
            if (directRoute.getCheckpoints().get(0).getEta().isShorterThan(sessionTimeLeft)) {
                optimalRoute = directRoute;
                alternativeLocations = (calculateAlternativeStops(directRoute, gasStationNeeded,
                        directRoute.getCheckpoints().get(0).getEta().dividedBy(2),
                        directRoute.getCheckpoints().get(0).getEta().dividedBy(3),
                        directRoute.getCheckpoints().get(0).getEta().dividedBy(4)));
            }

            //If there is no time left on this session
            else if (sessionTimeLeft.isEqual(Duration.ZERO)) {
                optimalRoute = getOptimizedRoute(directRoute, Duration.standardMinutes(5), gasStationNeeded);
                alternativeLocations = calculateAlternativeStops(directRoute, gasStationNeeded, Duration.standardMinutes(10),
                        Duration.standardMinutes(15), Duration.standardMinutes(20));
            }

            //If the location is within reach this day but not this session
            else if (!directRoute.getCheckpoints().get(0).getEta().isShorterThan(sessionTimeLeft) &&
                    directRoute.getCheckpoints().get(0).getEta().isShorterThan(regulationHandler.getThisDayTL(user.getHistory()).getTimeLeft())) {

                //If the ETA/2 is longer than time left on session
                if (directRoute.getCheckpoints().get(0).getEta().dividedBy(2).isLongerThan(sessionTimeLeft)) {
                    optimalRoute = getOptimizedRoute(directRoute, regulationHandler.getThisSessionTL(user.getHistory()).getTimeLeft(), gasStationNeeded);
                    alternativeLocations = calculateAlternativeStops(directRoute, gasStationNeeded,
                            sessionTimeLeft.dividedBy(2), sessionTimeLeft.dividedBy(3), sessionTimeLeft.dividedBy(4));
                } else {
                    optimalRoute = getOptimizedRoute(directRoute, directRoute.getCheckpoints().get(0).getEta().dividedBy(2), gasStationNeeded);
                    alternativeLocations = calculateAlternativeStops(directRoute, gasStationNeeded, sessionTimeLeft,
                            sessionTimeLeft.dividedBy(2), sessionTimeLeft.dividedBy(3));
                }
            }

            //If the location is not within reach this day (drive maximum distance)
            else if (!directRoute.getCheckpoints().get(0).getEta().isShorterThan(regulationHandler.getThisDayTL(user.getHistory()).getTimeLeft())) {
                optimalRoute = getOptimizedRoute(directRoute, regulationHandler.getThisSessionTL(user.getHistory()).getTimeLeft(), gasStationNeeded);
                alternativeLocations = calculateAlternativeStops(directRoute, gasStationNeeded,
                        sessionTimeLeft.dividedBy(2), sessionTimeLeft.dividedBy(3), sessionTimeLeft.dividedBy(4));
            } else {
                throw new InvalidRequestException("Something is not right here");
            }

            if (optimalRoute.getCheckpoints().size() > 0) {
                displayedRecommended = optimalRoute.getCheckpoints().get(0);
            } else {
                displayedRecommended = optimalRoute.getFinalDestination();
            }
        }
        return new PlannedRoute(optimalRoute, displayedRecommended, alternativeLocations);
    }

    /**
     * Get a list of one stop location close to each wanted ETA.
     *
     * @param directRoute fastest route without rest locations added.
     * @param stopsETA    times that stops are wanted in.
     * @return list if stop locations
     */
    private ArrayList<RouteLocation> calculateAlternativeStops(Route directRoute, boolean gasStationNeeded, Duration... stopsETA)
            throws InvalidRequestException, NoConnectionException {
        if (gasStationNeeded) {
            return calculateAlternativeGasStations(directRoute, stopsETA[0], (fuelTank.getMileage() * 1000) / 2,
                    (fuelTank.getMileage() * 1000) / 3, (fuelTank.getMileage() * 1000) / 4);
        } else {
            return calculateAlternativeRestLocations(directRoute, stopsETA);
        }
    }

    /**
     * Get a list of one stop location close to each wanted ETA.
     *
     * @param directRoute fastest route without rest locations added.
     * @param stopsETA    times that stops are wanted in.
     * @return list if stop locations
     */
    private ArrayList<RouteLocation> calculateAlternativeRestLocations(Route directRoute, Duration... stopsETA)
            throws InvalidRequestException, NoConnectionException {
        ArrayList<RouteLocation> incompleteInfo = new ArrayList<RouteLocation>();
        ArrayList<RouteLocation> completeInfo = new ArrayList<RouteLocation>();


        for (Duration eta : stopsETA) {
            LatLng tempCoordinate = findLatLngWithinReach(directRoute, eta, fuelTank.getMileage() * 1000);
            ArrayList<RouteLocation> response = placesProvider.getNearbyRestLocations(tempCoordinate);

            for (RouteLocation location : response) {
                if (directionsProvider.getETA(currentLocation, location).
                        isShorterThan(regulationHandler.getThisSessionTL(user.getHistory()).getTimeLeft())) {
                    incompleteInfo.add(location);
                    break; //Can not afford to do more calls to check the optimum one
                }
            }
        }

        //Creates new RouteLocations with all variables set
        for (RouteLocation incompleteLocation : incompleteInfo) {
            Route tempRoute = directionsProvider.getRoute(currentLocation, incompleteLocation);
            completeInfo.add(tempRoute.getFinalDestination());
        }
        return completeInfo;
    }

    /**
     * Alternative gas stations that are within distance and allowed driving time-
     *
     * @param directRoute The direct route with no calculated stops.
     * @param stopETA     Maximum time to drive this session.
     * @param distances   Rough distance to each location
     * @return a list with a gas station close to each given distance
     * @throws InvalidRequestException
     * @throws NoConnectionException
     */
    private ArrayList<RouteLocation> calculateAlternativeGasStations(Route directRoute, Duration stopETA, int... distances)
            throws InvalidRequestException, NoConnectionException {
        ArrayList<RouteLocation> incompleteInfo = new ArrayList<RouteLocation>();
        ArrayList<RouteLocation> completeInfo = new ArrayList<RouteLocation>();


        for (int tempDistance : distances) {
            LatLng tempCoordinate = findLatLngWithinReach(directRoute, stopETA, tempDistance);
            ArrayList<RouteLocation> response = placesProvider.getNearbyGasStations(tempCoordinate);

            for (RouteLocation location : response) {
                Route tempRoute = directionsProvider.getRoute(currentLocation, location);
                if (tempRoute.getFinalDestination().getEta().
                        isShorterThan(regulationHandler.getThisSessionTL(user.getHistory()).getTimeLeft()) &&
                        tempRoute.getDistance() < tempDistance) {
                    incompleteInfo.add(location);
                    break; //Can not afford to do more calls to check the optimum one
                }
            }
        }

        //Creates new RouteLocations with all variables set
        for (RouteLocation incompleteLocation : incompleteInfo) {
            Route tempRoute = directionsProvider.getRoute(currentLocation, incompleteLocation);
            completeInfo.add(tempRoute.getFinalDestination());
        }
        return completeInfo;
    }

    /**
     * Get optimized route with one rest location as a checkpoint.
     *
     * @param directRoute        Route from Google Directions without any rest or gas stops.
     * @param within             Within what time a rest should be made.
     * @param gasStationRequired True if the stop have to be a gas station
     * @return An optimized route with the most suitable rest location as a checkpoint.
     */
    private Route getOptimizedRoute(Route directRoute, Duration within, boolean gasStationRequired)
            throws InvalidRequestException, NoConnectionException {
        Route optimalRoute = null;
        LatLng optimalLatLong;
        ArrayList<RouteLocation> closeLocations;

        optimalLatLong = findLatLngWithinReach(directRoute, within, fuelTank.getMileage() * 1000);
        if (gasStationRequired) {
            closeLocations = placesProvider.getNearbyGasStations(optimalLatLong);
        } else {
            closeLocations = placesProvider.getNearbyRestLocations(optimalLatLong);
        }

        if (closeLocations.size() == 0) {
            if (gasStationRequired) {
                while (closeLocations.size() == 0) {
                    optimalLatLong = findLatLngWithinReach(directRoute, within, fuelTank.getMileage() * 1000);
                    closeLocations = placesProvider.getNearbyGasStations(optimalLatLong);
                }
            } else {
                Route tempRoute = directionsProvider.getRoute(currentLocation, new MapLocation(optimalLatLong));
                RouteLocation forcedLocation = new RouteLocation(optimalLatLong, "", tempRoute.getEta(),
                        Instant.now().plus(tempRoute.getEta()), tempRoute.getDistance());

                closeLocations.add(forcedLocation);
            }
        }

        //Just calculating the five best matches from Google
        for (int i = 0; i < 5 && i < closeLocations.size(); i++) {
            //Temporary creation
            LinkedList<MapLocation> tempList = new LinkedList<MapLocation>();

            tempList.add(closeLocations.get(i));
            if (checkpoints != null) {
                tempList.addAll(checkpoints);
            }

            //Checks if the restLocation is a possible stop and is faster than the previous
            Route temp = directionsProvider.getRoute(startLocation, finalDestination, tempList);

            if (temp.getCheckpoints().get(0).getEta().isShorterThan(regulationHandler.getThisSessionTL(user.getHistory()).getTimeLeft())) {
                if (optimalRoute == null) {
                    this.recommendedStop = closeLocations.get(i);
                    optimalRoute = temp;
                } else if (temp.getEta().isShorterThan(optimalRoute.getEta())) {
                    this.recommendedStop = closeLocations.get(i);
                    optimalRoute = temp;
                }
            }
        }
        return optimalRoute;
    }

    /**
     * Find the coordinate on the polyline that have the smallest delta value from the time and distance left.
     *
     * @param directRoute    Route from Google Directions without any rest or gas stops.
     * @param timeLeft       ETA that the coordinate should be close to.
     * @param withinDistance Distance that the LatLng has to be within in meters.
     * @return The coordinate that matches time left the best.
     */
    private LatLng findLatLngWithinReach(Route directRoute, Duration timeLeft, int withinDistance)
            throws InvalidRequestException, NoConnectionException {

        double[] divider = {0, 0.125, 0.25, 0.375, 0.5, 0.625, 0.750, 0.875, 1};
        ArrayList<LatLng> coordinates = directRoute.getDetailedPolyline();
        int[] index = new int[9];
        int topIndex = coordinates.size() - 1;
        int bottomIndex = 0;
        Route routeToLatLng;

        // Type of binary search with eight instead of two parts to minimize google requests
        do {
            LatLng[] baseLatLng = new LatLng[9];

            for (int i = 0; i < 9; i++) {
                index[i] = (int) ((topIndex - bottomIndex) * divider[i] + bottomIndex);
                baseLatLng[i] = coordinates.get(index[i]);
            }

            ArrayList<MapLocation> tempLocations = new ArrayList<MapLocation>();
            for (int i = 1; i < 8; i++) {
                tempLocations.add(new MapLocation(baseLatLng[i]));
            }
            routeToLatLng = directionsProvider.getRoute(new MapLocation(baseLatLng[0]), new MapLocation(baseLatLng[8]), tempLocations);
            for (int i = 0; i < 8; i++) {
                //Checks if the LatLng matches end conditions
                if ((routeToLatLng.getCheckpoints().get(i).getEta().isLongerThan(timeLeft.minus(Duration.standardMinutes(5))) &&
                        routeToLatLng.getCheckpoints().get(i).getEta().isShorterThan(timeLeft)) ||
                        routeToLatLng.getCheckpoints().get(i).getDistance() > withinDistance + 10000 &&
                                routeToLatLng.getCheckpoints().get(i).getDistance() < withinDistance) {
                    return routeToLatLng.getCheckpoints().get(i).getLatLng();
                }

                if (routeToLatLng.getCheckpoints().get(i).getEta().isShorterThan(timeLeft.minus(Duration.standardMinutes(5))) &&
                        routeToLatLng.getCheckpoints().get(i).getDistance() < withinDistance) {
                    bottomIndex = index[i];
                } else {
                    topIndex = index[i];
                    break;
                }
            }
        } while (topIndex - bottomIndex > 1);

        return directRoute.getDetailedPolyline().get(bottomIndex);
    }
}