/**
 * Application events that decouple features which would otherwise call each other in a cycle. Listeners use
 * {@code @EventListener} (synchronous, same thread — exactly like the direct calls in the Node services) and must
 * not throw: they log failures, because a side effect never undoes the action that published the event.
 */
package com.campusconnect.core.events;
