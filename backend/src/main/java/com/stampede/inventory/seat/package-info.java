/**
 * The seat state machine (M1.1, D-014). Internal to inventory: only inventory writes event_seats, so
 * other modules change seats through inventory's api, never through these types (D-007).
 */
package com.stampede.inventory.seat;
