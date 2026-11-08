package com.stampede.inventory.seed;

/** A seed file is invalid, or doesn't match data already in the database. Nothing is written. */
public class SeedException extends RuntimeException {

    public SeedException(String message) {
        super(message);
    }

    public SeedException(String message, Throwable cause) {
        super(message, cause);
    }
}
