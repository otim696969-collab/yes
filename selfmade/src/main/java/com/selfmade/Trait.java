package com.selfmade;

/**
 * The eight hidden Identity Attributes. The game never shows these to the player as numbers;
 * the "omen" is the only thing that ever hints at them.
 */
public enum Trait {
	WILL("Something in you refuses to lie down."),
	EGO("Your shadow stands a little taller than you do."),
	EMPATHY("Something at your side has learned to flinch when others do."),
	RESOLVE("You are harder to move than you used to be."),
	FEAR("Your shadow has started leaving before you do."),
	CURIOSITY("Something behind you keeps looking over the next hill."),
	VIOLENCE("The thing that follows you has begun to grow edges."),
	ATTACHMENT("There is a place you keep turning back toward.");

	public final String omen;

	Trait(String omen) {
		this.omen = omen;
	}

	public String label() {
		return name().charAt(0) + name().substring(1).toLowerCase();
	}
}
