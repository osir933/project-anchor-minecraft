/**
 * Connects the engine to a game that hosts it, such as Minecraft, without knowing anything about that game.
 *
 * <p>The host describes each kind of block once as a {@link io.github.osir933.anchor.core.host.BlockAppearance}
 * and refers to blocks by its own integer ids. {@link io.github.osir933.anchor.core.host.HostedWorld} imports
 * the host's sections, follows its block changes, runs heat where something is happening, and tells the host
 * which blocks it should now show differently, such as ice that has melted.
 * {@link io.github.osir933.anchor.core.host.ImportPlanner} decides which sections to bring in around the
 * players, a few at a time.
 */
package io.github.osir933.anchor.core.host;
