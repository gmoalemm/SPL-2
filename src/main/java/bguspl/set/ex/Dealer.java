package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    private long[] freezeTimes;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());

        freezeTimes = new long[this.players.length];
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");

        while (!shouldFinish()) {
            placeCardsOnTable();
            updateTimerDisplay(true);
            timerLoop();
            // updateTimerDisplay(false);
            removeAllCardsFromTable();
        }

        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did
     * not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        // TODO implement

        // TODO: handle cases when a player has not finished doing something when this
        // method is called

        for (Player player : this.players) {
            player.terminate();
        }

        this.terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     * 
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        // TODO implement

        Integer currentPlayer;
        ArrayList<Integer> currentPlayerTokens = new ArrayList<Integer>(3);
        int i;

        while (!this.table.waitingPlayers.isEmpty()) {
            currentPlayer = this.table.waitingPlayers.poll();

            i = 0;

            // find the cards that the current player chose
            for (int s = 0; s < table.tokens.length; s++) {
                if (table.tokens[s][currentPlayer]) {
                    currentPlayerTokens.add(i++, table.slotToCard[s]);
                }
            }

            if (!env.util.findSets(currentPlayerTokens, 1).isEmpty()) {
                this.players[currentPlayer].point();

                for (Integer card : currentPlayerTokens) {
                    table.removeCard(table.cardToSlot[card]);
                }

                updateTimerDisplay(true);
                freezeTimes[currentPlayer] = System.currentTimeMillis() + env.config.pointFreezeMillis;
            } else {
                this.players[currentPlayer].penalty();

                for (Integer card : currentPlayerTokens) {
                    table.removeToken(currentPlayer, table.cardToSlot[card]);
                }

                freezeTimes[currentPlayer] = System.currentTimeMillis() + env.config.penaltyFreezeMillis;
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        Collections.shuffle(deck);

        int maxSlot = this.env.config.tableSize;

        for (int slot = 0; slot < maxSlot; slot++) {
            if (this.table.slotToCard[slot] == null) {
                this.table.placeCard(this.deck.remove(0), slot);
            }
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some
     * purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        // TODO implement

        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset) {
            reshuffleTime = System.currentTimeMillis() +
                    this.env.config.turnTimeoutMillis;
        } else {
            long timeLeft = reshuffleTime - System.currentTimeMillis();

            this.env.ui.setCountdown(timeLeft < 0 ? 0 : timeLeft, timeLeft < this.env.config.turnTimeoutWarningMillis);

            for (int player = 0; player < players.length; player++) {
                this.env.ui.setFreeze(player, freezeTimes[player] - System.currentTimeMillis());
            }
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        int maxSlot = this.env.config.tableSize;

        for (int slot = 0; slot < maxSlot; slot++) {
            this.deck.add(this.table.slotToCard[slot]);
            this.table.removeCard(slot);
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        // TODO implement
    }
}
