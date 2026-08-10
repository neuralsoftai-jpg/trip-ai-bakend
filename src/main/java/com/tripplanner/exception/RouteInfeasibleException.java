package com.tripplanner.exception;

public class RouteInfeasibleException extends RuntimeException {
    public RouteInfeasibleException(String message) {
        super(message);
    }
}
