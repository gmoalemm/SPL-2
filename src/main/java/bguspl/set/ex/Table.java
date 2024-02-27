package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 * 
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    /** An array for each slot and in it an array for each player. */
    protected final boolean[][] tokens;

    /** An array that holds the number of tokens each player has placed. */
    protected int[] tokensPerPlayer;

    /**
     * Used to lock slots when needed so that a card removal and a token addition
     * cannot happen simultaneously.
     */
    private Object[] slotFlags;

    public Object xPressed;

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if
     *                   none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if
     *                   none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {
        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        this.tokens = new boolean[env.config.tableSize][env.config.players];
        this.tokensPerPlayer = new int[env.config.players];
        this.slotFlags = new Object[env.config.tableSize];

        for (int i = 0; i < env.config.tableSize; i++) {
            this.slotFlags[i] = new Object();
        }

        this.xPressed = new Object();
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {
        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the
     * table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted()
                    .collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(
                    sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     * 
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * 
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        try {
            synchronized (xPressed) {
                xPressed.wait(env.config.tableDelayMillis);
            }

            // Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {
        }

        cardToSlot[card] = slot;
        slotToCard[slot] = card;

        env.ui.placeCard(card, slot);
    }

    /**
     * Removes a card from a grid slot on the table.
     * 
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot) {
        try {
            synchronized (xPressed) {
                xPressed.wait(env.config.tableDelayMillis);
            }

            // Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {
        }

        synchronized (slotFlags[slot]) {
            cardToSlot[slotToCard[slot]] = null;
            slotToCard[slot] = null;

            for (int i = 0; i < tokens[slot].length; i++) {
                if (tokens[slot][i]) {
                    removeToken(i, slot);
                }
            }

            env.ui.removeCard(slot);
        }
    }

    /**
     * Places a player token on a grid slot.
     * 
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public void placeToken(int player, int slot) {
        synchronized (slotFlags[slot]) {
            if (!removeToken(player, slot) // the player did not have a token on this slot
                    && tokensPerPlayer[player] < env.config.featureSize // the player did not reach the max. num. of
                                                                        // tokens
                    && slotToCard[slot] != null) // the slot contains a card
            {
                env.ui.placeToken(player, slot);
                tokens[slot][player] = true;
                tokensPerPlayer[player]++;
            }
        }
    }

    /**
     * Removes a token of a player from a grid slot.
     * 
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return - true iff a token was successfully removed.
     */
    public boolean removeToken(int player, int slot) {
        if (tokens[slot][player]) {
            env.ui.removeToken(player, slot);
            tokens[slot][player] = false;
            tokensPerPlayer[player]--;

            return true;
        }

        return false;
    }

    public List<Integer> getCardsOnTable() {
        List<Integer> cardsOnTable = new LinkedList<Integer>();

        for (Integer card : slotToCard) {
            if (card != null) {
                cardsOnTable.add(card);
            }
        }

        return cardsOnTable;
    }
}
