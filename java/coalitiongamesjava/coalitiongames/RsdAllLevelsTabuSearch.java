package coalitiongames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public abstract class RsdAllLevelsTabuSearch {
    
    /*
     * rsdOrder: first item is the index of the first agent to go.
     * second item is the index of second agent to go, etc.
     * example:
     * 1 2 0 -> agent 1 goes, then agent 2, then agent 0.
     */
    public static SearchResult 
        rsdTabuSearchAllLevelsOptimalSizes(
        final List<Agent> agents,
        final GammaZ gammaZ,
        final int kMax,
        final List<Integer> rsdOrder
    ) {
        final List<Integer> optimalTeamSizeRange =
            RsdUtil.getOptimalTeamSizeRange(agents.size(), kMax);
        final int optimalKMin = optimalTeamSizeRange.get(0);
        final int optimalKMax = optimalTeamSizeRange.get(1);
        return rsdTabuSearchAllLevels(
            agents, gammaZ, optimalKMax, optimalKMin, rsdOrder
        );
    }

    /**
     * All-levels RSD tabu search works as follows. 
     * 
     * If the grand coalition has size <= kMax, this is returned.
     * 
     * An approximate CEEI
     * is found by tabu search, restricting the number of agents per team
     * to feasible numbers in {kMin, kMin + 1, . . ., kMax}, as given by
     * RsdUtil.getFeasibleNextTeamSizes(). 
     * Note that this requires a modification
     * of tabu search, to take a list of legal team sizes for tabu search, and
     * at each step, let agents choose their favorite affordable bundle from
     * the MIP resulting from any of these min/max pair ranges of sizes.
     * 
     * The RSD order is then used.
     * The first agent chooses its favorite affordable bundle
     * of teammates that leaves a feasible number left, 
     * and all those teammates are "out" of consideration and
     * finally allocated to this team. If there is no affordable
     * bundle of the required size kMin left, the agent chooses
     * its favorite remaining bundle.
     * 
     * If at any stage the remaining number of agents is <= kMax,
     * all remaining agents are placed on the same team.
     * 
     * Else if the remaining number of agents is > kMax, then 
     * if the next agent remaining in RSD order still has all members of its
     * favorite affordable, feasible bundle (from the tabu search) available, 
     * it takes that bundle, and so on.
     * 
     * If at some point the next agent remaining in RSD order 
     * does not have all the agents of its favorite affordable, 
     * feasible bundle available, then we run a new tabu search for the 
     * remaining agents, allowing only the feasible numbers
     * of agents per team to be chosen. Continue until all 
     * agents are assigned to teams.
     * 
     * @param agents a list of all agents with their budgets and preferences.
     * if kMin > 1, then
     * the agents' budgets must be strictly decreasing from early (preferred)
     * to late (less preferred) RSD order, or the market may not clear, because
     * the first RSD order agent may not be able to afford kMin items.
     * @param gammaZ an error function to use for updating prices
     * @param kMax maximum agents per team, including self
     * @param kMin minimum agents per team, including self
     * @param rsdOrder shuffled list of numbers from {0, 1, . . . (n - 1)}, 
     * indicating the random serial dictatorship order to use, based on
     * indexes of Agents in "agents" list.
     * first item is the index of the first agent to go.
     * second item is the index of second agent to go, etc.
     * example:
     * 1 2 0 -> agent 1 goes, then agent 2, then agent 0.
     * @return a SearchResult, including an allocation, price vector,
     * and other data
     */   
    public static SearchResult 
        rsdTabuSearchAllLevels(
        final List<Agent> agents,
        final GammaZ gammaZ,
        final int kMax,
        final int kMin,
        final List<Integer> rsdOrder
    ) {
        final int minimumAgents = 4;
        assert agents != null && agents.size() >= minimumAgents;
        assert rsdOrder.size() == agents.size();
        assert gammaZ != null;
        final int n = agents.size();
        assert kMax <= n;
        if (!TabuSearch.checkKRange(n, kMin, kMax)) {
            throw new IllegalArgumentException();
        }
        
        // if grand coalition is feasible, assign it and return
        if (kMax >= agents.size()) {
            return RsdUtil.getGrandCoalition(agents, kMax, rsdOrder);
        }

        for (int i = 0; i < agents.size() - 1; i++) {
            if (
                agents.get(rsdOrder.get(i)).getBudget() 
                < agents.get(rsdOrder.get(i + 1)).getBudget()
            ) {
                throw new IllegalStateException(
                    "later rsdOrder agent has " 
                    + "higher budget; market may not clear"
                );                
            }
        }
        
        // time the duration of the search to the millisecond
        final long searchStartMillis = new Date().getTime();
        final List<Integer> initialFeasibleTeamSizes = 
            RsdUtil.getFeasibleNextTeamSizes(n, kMin, kMax);
        
        // holds the index of the captain of each team, in order.
        final List<Integer> captainIndexes = new ArrayList<Integer>();

        // currentSearchResult sets the prices of all agents. if it has market
        // clearing error, not all agents 
        // will get their allocations from this result.
        SearchResult currentSearchResult = TabuSearch.tabuSearchRanges(
            agents, gammaZ, initialFeasibleTeamSizes);
        int tabuSearchCalls = 1;
        final SearchResult initialSearchResult = currentSearchResult;
        
        // an allocation. each player appears in exactly 1 List<Integer>, 
        // or row. there is one row per team, instead of 1 row per player.
        // columns (items) in each List<Integer> correspond to players, by index
        // in initialResult.
        final List<List<Integer>> allocation = new ArrayList<List<Integer>>();
        // indexes in initialResult of players already allocated to a team.
        final List<Integer> takenAgentIndexes = new ArrayList<Integer>();
        
        // iterate over players in RSD order, by their index in initialResult
        for (final Integer agentIndex: rsdOrder) {
            // if the player has already been assigned to a team, skip it.
            if (takenAgentIndexes.contains(agentIndex)) {
                continue;
            }
            
            captainIndexes.add(agentIndex);
            
            if (MipGenerator.DEBUGGING) {
                // check for duplicates in takenAgentIndexes.
                Set<Integer> takenAgentIndexSet = new HashSet<Integer>();
                takenAgentIndexSet.addAll(takenAgentIndexes);
                if (takenAgentIndexes.size() != takenAgentIndexSet.size()) {
                    throw new IllegalStateException(
                        "Duplicate entry: " + takenAgentIndexes
                    );
                }
                for (int i = 0; i < agents.size(); i++) {
                    if (!rsdOrder.contains(i)) {
                        throw new IllegalArgumentException(
                            "Missing rsdOrder index: " + i
                        );
                    }
                }
            }
            
            // get count of agents that have not yet been assigned to a team.
            final int agentsLeft = agents.size() - takenAgentIndexes.size();
            assert agentsLeft > 0;
            
            // feasible team sizes include self as a team member. 
            // an agent can demand 1 less than any value in this list.
            final List<Integer> feasibleTeamSizes = 
                RsdUtil.getFeasibleNextTeamSizes(agentsLeft, kMin, kMax);
            if (feasibleTeamSizes.isEmpty()) {
                throw new IllegalStateException();
            }
            
            // if it's feasible to assign all remaining agents to same team,
            // do this, even if it would not be "affordable."
            // then break out of the loop over all agents to return the results.
            if (agentsLeft <= kMax) {
                final List<Integer> demand = new ArrayList<Integer>();
                for (int i = 0; i < agents.size(); i++) {
                    if (takenAgentIndexes.contains(i)) {
                        demand.add(0);
                    } else {
                        demand.add(1);
                    }
                }
                allocation.add(demand);
                break;
            }
            
            /*
             * check if agent can be allocated its favorite 
             * affordable bundle from currentSearchResult.
             * -- can't demand any agent that is already "taken"
             */
            // check if currentSearchResult demand 
            // for the current agent is feasible.
            
            final int indexInCurrentSearchResult = 
                currentSearchResult.getAgents().indexOf(agents.get(agentIndex));
            final List<Integer> currentAgentDemand = 
                currentSearchResult.getAllocation().
                    get(indexInCurrentSearchResult);
            // add 0 demand for agents already on teams
            if (currentAgentDemand.size() < agents.size()) {
                Collections.sort(takenAgentIndexes);
                for (int i = 0; i < agents.size(); i++) {
                    final Agent agentToFind = agents.get(i);
                    if (
                        !currentSearchResult.getAgents().contains(agentToFind)
                    ) {
                        currentAgentDemand.add(i, 0);
                    }
                }
            }
            assert currentAgentDemand.size() == agents.size();
            
            // check if the demand tries to take a player already taken.
            boolean hasDemandConflict = false;
            for (int i = 0; i < currentAgentDemand.size(); i++) {
                // if the agent demands an agent already taken . . .
                if (
                    currentAgentDemand.get(i) == 1 
                    && takenAgentIndexes.contains(i)
                ) {
                    hasDemandConflict = true;
                    break;
                }
            }
            if (!hasDemandConflict) {
                // check if the demanded team size is feasible.
                // this also stops an agent who didn't get kMin agents
                // in the last tabuSearch result from being given too few
                // agents.
                final int teamSize = RsdUtil.getTeamSize(currentAgentDemand);
                if (feasibleTeamSizes.contains(teamSize)) {
                    // the allocation is feasible. make this allocation.
                    allocation.add(currentAgentDemand);
                    // indicate that all taken agents have been taken.
                    for (int i = 0; i < currentAgentDemand.size(); i++) {
                        if (currentAgentDemand.get(i) == 1) {
                            takenAgentIndexes.add(i);
                        }
                    }
                    assert takenAgentIndexes.contains(agentIndex);
                    // done with this agent
                    continue;
                }
            }
            
            assert !takenAgentIndexes.contains(agentIndex);
            // currentAgentDemand demand for this agent was not feasible.
            final List<Integer> newAgentDemand = new ArrayList<Integer>();
            // if this is the only agent left, don't bother with tabu search
            if (takenAgentIndexes.size() == agents.size() - 1) {
                for (int i = 0; i < agents.size(); i++) {
                    if (i == agentIndex) {
                        newAgentDemand.add(1);
                    } else {
                        newAgentDemand.add(0);
                    }
                }
            } else {
                // former demand of this agent no longer feasible.
                // must run a new tabu search.
                final List<Agent> remainingAgents = new ArrayList<Agent>();
                for (int i = 0; i < agents.size(); i++) {
                    if (!takenAgentIndexes.contains(i)) {
                        final Agent agentToCopy = agents.get(i);
                        // subset the "values" list, to include only those
                        // other agents that are not in takenAgentIndexes
                        // subset the "uuidsSubset" list in the same way,
                        // to indicate which agents' values are left
                        final List<Double> valuesSubset = 
                            new ArrayList<Double>();
                        final List<UUID> uuidsSubset = new ArrayList<UUID>();
                        for (int j = 0; j < agents.size(); j++) {
                            if (!takenAgentIndexes.contains(j) && i != j) {
                                // this agent's value 
                                // and uuid should be retained.
                                final UUID uuidOfAgent = 
                                    agents.get(j).getUuid();
                                final int indexOfAgent = 
                                    agentToCopy.getAgentIdsForValues().
                                    indexOf(uuidOfAgent);
                                if (indexOfAgent == -1) {
                                    // if an agent other than the 
                                    // self agent has not been
                                    // taken yet, its value should 
                                    // still be present.
                                    throw new IllegalStateException();
                                }
                                valuesSubset.add(
                                    agentToCopy.getValues().get(indexOfAgent)
                                );
                                uuidsSubset.add(uuidOfAgent);
                            }
                        }
                        assert valuesSubset.size() == agents.size() 
                            - takenAgentIndexes.size() - 1;
                        final Agent copyOfAgent = new Agent(
                            valuesSubset, 
                            uuidsSubset,
                            agentToCopy.getBudget(), 
                            agentToCopy.getId(), 
                            agentToCopy.getUuid()
                        );
                        remainingAgents.add(copyOfAgent);
                    }
                }
                
                // update prices for remaining agents
                currentSearchResult = TabuSearch.tabuSearchRanges(
                    remainingAgents, gammaZ, feasibleTeamSizes
                );
                
                tabuSearchCalls++;
                
                /* set up MIP over remaining agents, for the current agent
                * -- prices and budgets are now from currentSearchResult,
                * NOT the initialSearchResult
                * -- remove any agents already "taken" from the MIP
                * number
                * assign the given bundle to the agent and remove its 
                * contents from consideration.    
                */
                final Agent currentAgent = agents.get(agentIndex);
                final List<Double> values = RsdUtil.getValueListWithout(
                    currentAgent.getValues(), agentIndex, takenAgentIndexes
                );
                final List<Integer> emptyList = new ArrayList<Integer>();
                final int agentIndexInRemainingAgents = 
                    remainingAgents.indexOf(currentAgent);
                final List<Double> prices = getPriceListWithout(
                    currentSearchResult.getPrices(), 
                    agentIndexInRemainingAgents, emptyList
                );
                // final MipGenerator mipGenerator = new MipGeneratorGLPK();
                final MipGenerator mipGenerator = new MipGeneratorCPLEX();
                final double maxPrice = MipGenerator.MIN_BUDGET 
                    + MipGenerator.MIN_BUDGET / agents.size();
                final MipResult mipResult = mipGenerator.getLpSolution(
                    values, prices, currentAgent.getBudget(), 
                    feasibleTeamSizes, maxPrice
                );
                newAgentDemand.addAll(mipResult.getRoundedColumnValues());
                
                // add 0 demand for agents already on teams,
                // and 1 demand for the self agent
                Collections.sort(takenAgentIndexes);
                for (int i = 0; i < agents.size(); i++) {
                    if (takenAgentIndexes.contains(i)) {
                        newAgentDemand.add(i, 0);
                    }
                    if (i == agentIndex) {
                        newAgentDemand.add(i, 1);
                    }
                }
            }

            assert newAgentDemand.size() == agents.size();
            
            // if the current agent received the empty bundle,
            // which can occasionally happen when an A-CEEI is found by
            // tabu search, even though current agent has the strictly
            // greatest budget.
            // let current agent pick its favorite bundle from
            // remaining agents.
            if (RsdUtil.getTeamSize(newAgentDemand) < kMin) {
                assert RsdUtil.getTeamSize(newAgentDemand) == 1;
                final List<Integer> rsdDemand = 
                    getRsdChoices(agentIndex, agents, 
                    takenAgentIndexes, Collections.min(feasibleTeamSizes)
                );
                allocation.add(rsdDemand);
                for (int i = 0; i < rsdDemand.size(); i++) {
                    if (rsdDemand.get(i) == 1) {
                        takenAgentIndexes.add(i);
                    }
                }
                assert takenAgentIndexes.contains(agentIndex);                
                continue;
            }
            
            assert RsdUtil.getTeamSize(newAgentDemand) >= kMin;
            
            // make this allocation.
            allocation.add(newAgentDemand);
            // indicate that all taken agents have been taken.
            for (int i = 0; i < newAgentDemand.size(); i++) {
                if (newAgentDemand.get(i) == 1) {
                    takenAgentIndexes.add(i);
                }
            }
            assert takenAgentIndexes.contains(agentIndex);
        }
        
        final List<Double> error = new ArrayList<Double>();
        for (int i = 0; i < agents.size(); i++) {
            error.add(0.0);
        }
        final double errorSize = 0.0;
        final double maxBudget = MipGenerator.MIN_BUDGET 
            + MipGenerator.MIN_BUDGET / agents.size();
        final long searchDurationMillis = 
            new Date().getTime() - searchStartMillis;
        final List<List<Integer>> resultAllocation = 
            RsdUtil.getAllocation(allocation);
        final double similarity = 
            PreferenceAnalyzer.getMeanPairwiseCosineSimilarity(agents);
        final SearchResult result = new SearchResult(
            initialSearchResult.getPrices(), resultAllocation, error, 
            errorSize, 0, kMax, maxBudget, agents, searchDurationMillis,
            rsdOrder, initialSearchResult.getBestErrorValues(),
            initialSearchResult.getPriceUpdateSources(),
            tabuSearchCalls, captainIndexes, similarity
        );
        return result;
    }
    
    public static List<Double> getPriceListWithout(
        final List<Double> partialPriceList, 
        final int agentIndex, 
        final List<Integer> takenAgentIndexes
    ) {
        // self index should not be in takenAgentIndexes, 
        // because indexesToRemove
        // should only include indexes of agents already assigned to teams,
        // and the self agent is the next agent 
        // to act as "captain," so it should not be on a team yet.
        assert !takenAgentIndexes.contains(agentIndex);
        
        final List<Double> result = new ArrayList<Double>();
        for (Double originalItem: partialPriceList) {
            result.add(originalItem);
        }
        
        final List<Integer> myTakenAgentIndexes = new ArrayList<Integer>();
        for (final Integer takenAgentIndex: takenAgentIndexes) {
            myTakenAgentIndexes.add(takenAgentIndex);
        }
        
        // remove the self agent price also
        myTakenAgentIndexes.add(agentIndex);
        // sort the indexes increasing
        Collections.sort(myTakenAgentIndexes);
        
        final int itemsRemovedBeforeAgentIndex = 
            myTakenAgentIndexes.indexOf(agentIndex);
        // index of agent in result is 
        // (agentIndex - itemsRemovedBeforeAgentIndex).
        result.remove(agentIndex - itemsRemovedBeforeAgentIndex);
        return result; 
    }
    
    /**
     * 
     * @param captainIndex index of "team captain" in agents list
     * @param agents list of all agents
     * @param takenAgentIndexes indexes in agents list of already taken agents
     * @param teamSize size of the team to choose, including the captain
     * @return a list of 0's and 1's of length same as agents list,
     * where 1 indicates the player is on the team. there should be
     * teamSize number of 1's, including a 1 at item captainIndex
     */
    public static List<Integer> getRsdChoices(
        final int captainIndex,
        final List<Agent> agents,
        final List<Integer> takenAgentIndexes,
        final int teamSize
    ) {
        List<Integer> team = new ArrayList<Integer>();
        team.add(captainIndex);
        final Agent captain = agents.get(captainIndex);
        for (final UUID aUuid: captain.getAgentIdsHighValueToLow()) {
            boolean isTaken = false;
            for (final Integer takenAgentIndex: takenAgentIndexes) {
                if (agents.get(takenAgentIndex).getUuid().equals(aUuid)) {
                    isTaken = true;
                    break;
                }
            }
            if (!isTaken) {
                int index = -1;
                for (int i = 0; i < agents.size(); i++) {
                    if (agents.get(i).getUuid().equals(aUuid)) {
                        index = i;
                        break;
                    }
                }
                assert index != -1;
                team.add(index);
                if (team.size() == teamSize) {
                    break;
                }
            }
        }
        
        assert team.contains(captainIndex);
        assert team.size() == teamSize;
        
        final List<Integer> result = new ArrayList<Integer>();
        for (int i = 0; i < agents.size(); i++) {
            if (team.contains(i)) {
                result.add(1);
            } else {
                result.add(0);
            }
        }
        
        return result;
    }
}
