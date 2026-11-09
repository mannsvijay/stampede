/**
 * Inventory strategies (PRD 6.2, D-015). Internal to inventory: other modules hold seats through
 * inventory's api (M1.4), which picks the event's strategy; they never call a strategy directly (D-007).
 */
package com.stampede.inventory.strategy;
