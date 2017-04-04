package com.pedroedrasousa.tiling;

import com.pedroedrasousa.tiling.comparator.SolutionComparatorFactory;
import com.pedroedrasousa.tiling.comparator.SolutionMostNbrTilesComparator;
import com.pedroedrasousa.tiling.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CutListService {

    private final static Logger logger = LoggerFactory.getLogger(CutListService.class);

    @Autowired
    private StockPanelPicker stockPanelPicker;

    @Autowired
    private RunningTasks runningTasks;

    private void sort(List<Solution> solutions, Configuration cfg) {

        List<Comparator> solutionComparators = new ArrayList<>();

        // Solutions without all fitted tiles will go last
        solutionComparators.add(new SolutionMostNbrTilesComparator());

        for (String priotity : cfg.getPriorities()) {
            solutionComparators.add(SolutionComparatorFactory.getSolutionComparator(priotity));
        }

        Collections.sort(solutions, new Comparator<Solution>() {
            @Override
            public int compare(Solution o1, Solution o2) {

                int diff = 0;

                for (Comparator<Solution> solutionComparator : solutionComparators) {
                    diff = solutionComparator.compare(o1, o2);
                    if (diff != 0) {
                        break;
                    }
                }

                return diff;
            }
        });
    }

    void computeSolutions(List<TileDimensions> tiles, List<Solution> solutions, Configuration cfg, int accuracyFactor) {

        // Loop through all the titles to be fitted
        for (TileDimensions tile : tiles) {

            List<Solution> newSolutions = new ArrayList<>();
            boolean fitted = false;

            HashMap<String, Integer> depths = new HashMap<>();

            // Loop through all solutions to fit the tiles
            for (Iterator<Solution> iterator = solutions.iterator(); iterator.hasNext(); ) {
                Solution solution = iterator.next();

                // DEBUG: start
                String key = String.format("%1$20s", solution.getBasesAsString()) + " - depth[" + solution.getMaxDepth() + "]";
                if (depths.containsKey(key)) {
                    depths.put(key, depths.get(key) + 1);
                } else {
                    depths.put(key, 1);
                }
                // DEBUG: end

                for (Mosaic mosaic : solution.getMosaics()) {

                    List<Mosaic> newMosaics = new ArrayList<>();
                    add(tile, mosaic, newMosaics, cfg.getCutThickness(), cfg.getAllowTileRotation());

                    for (Mosaic newMosaic : newMosaics) {
                        Solution newSolution = new Solution(solution, mosaic);  // Copy the solution but exclude the mosaic that will be replaced by the new possibility
                        newSolution.addMosaic(newMosaic);                       // Add the new possibility
                        newSolutions.add(newSolution);                          // Add this new solution to list
                    }

                    if (newMosaics.size() > 0) {
                        fitted = true;
                        break;
                    }
                }

                if (fitted == true) {
                    iterator.remove();
                } else {
                    solution.getNoFitTiles().add(tile);
                }
            }

// TODO:
//            for (Solution solution : newSolutions) {
//                solution.convertTilesToImmutable();
//            }

            solutions.addAll(newSolutions);

            List<Solution> solutionsToRemove = new ArrayList<>();
            sort(solutions, cfg);
            solutionsToRemove.addAll(solutions.subList(Math.min(solutions.size() - 1, accuracyFactor/*(int) (accuracyFactor * 500.0f)*/), solutions.size() - 1));

            solutions.removeAll(solutionsToRemove);

            for (String depth : depths.keySet()) {
                //logger.info(depth + " - qty[" + depths.get(depth) + "]");
            }
        }
    }

    /**
     * Recalculates possibilities list.
     * Possibilities list will contain the root nodes of every tree on witch tiles will fit.
     */
    // TODO: should return solution not dto?
    public TillingResponseDTO compute(List<TileDimensions> tilesToFit, List<TileDimensions> stockTiles, Configuration cfg) {

        // Validate if tiles were provided
        if (tilesToFit == null || tilesToFit.size() == 0) {
            TillingResponseDTO tillingResponseDTO = new TillingResponseDTO();
            tillingResponseDTO.setReturnCode("1");
            return tillingResponseDTO;
        }

        // Validate if stock tiles were provided
        if (stockTiles == null || stockTiles.size() == 0) {
            TillingResponseDTO tillingResponseDTO = new TillingResponseDTO();
            tillingResponseDTO.setReturnCode("2");
            return tillingResponseDTO;
        }

        logger.info("STARTING... nbrTilesToFit[" + tilesToFit.size() + "] nbrBaseTiles[" + stockTiles.size() + "]");
        long startTime = System.currentTimeMillis();

        // Create task
        runningTasks.getTasks().add(new RunningTasks.Task(cfg.getTaskId(), "Initializing..."));


        List<Solution> allSolutions = new ArrayList<>();

        // Create a list with all distinct tile dimensions
        HashSet<String> distincTileDimensions = new HashSet<>();
        for (TileDimensions tileDimensions : tilesToFit) {
            distincTileDimensions.add(tileDimensions.toString());
        }

        // Get all possible combinations by permuting the order in witch the tiles are fited
        List<List<String>> combinations = Permutation.<String>generatePermutations(new ArrayList<>(distincTileDimensions));

        // Create lists sorted according to the calculated permutations
        List<List<TileDimensions>> tilesPermutations = new ArrayList<>();
        for (List<String> combination : combinations) {
            ArrayList<TileDimensions> solutionPermutation = new ArrayList<>(tilesToFit);
            tilesPermutations.add(solutionPermutation);
            Collections.sort(solutionPermutation, Comparator.comparingInt(o -> combination.indexOf(o.toString())));
        }

        List<StockSolution> stockSolutionsToExclude = new ArrayList<>();

        int spare = 0;
        int startWith = 1;

        boolean done = false;
        while (!done) {

            StockSolution stockSolution = stockPanelPicker.getCandidateStockSolutions(tilesToFit, stockTiles, 0f, spare, stockSolutionsToExclude, startWith);
            if (stockSolution == null) {
                spare++;
                stockSolution = stockPanelPicker.getCandidateStockSolutions(tilesToFit, stockTiles, 0f, spare, stockSolutionsToExclude, startWith);

                if (stockSolution == null) {
                    // No more possible stock solutions
                    logger.info("Couldn't find a suitable solution", stockSolution);
                    break;
                }
            }
            stockSolutionsToExclude.add(stockSolution);

            // Calculate the required area for fitting every tile.
            int requiredArea = 0;
            for (TileDimensions tile : tilesToFit) {
                requiredArea += tile.getArea();
            }
            float usedArea = (float)requiredArea / (float)stockSolution.getArea();
            int discardAbove = (int)(500.0f * Math.pow(usedArea, 3.0f));
            discardAbove = Math.max(discardAbove, 100);

            // TODO: Only for debug purposes
            if (cfg.getAccuracyFactor() > 0) {
                discardAbove = cfg.getAccuracyFactor();
            }
            logger.info("Used area [{}] accuracy[{}]", usedArea, discardAbove);

            logger.info("Trying stock {}", stockSolution);



            // Iterate through all permutations
            int permutationIndex = -1;
            for (List<TileDimensions> tilesPermutation : tilesPermutations) {
                permutationIndex++;

                logger.info("Permutation {}/{} on stock {} discardAbove[{}]", permutationIndex + 1, tilesPermutations.size(), stockSolution, discardAbove);
                RunningTasks.Task task2 = runningTasks.getTask(cfg.getTaskId());
                if (task2 != null) {
                    task2.setStatusMessage("Trying permutation " + (permutationIndex  + 1) + "/" + tilesPermutations.size() + " on stock " + stockSolution);
                }


                // Clone the candidate stock solutions
                List<Solution> solutions = new ArrayList<>();
                solutions.add(new Solution(stockSolution));

                computeSolutions(tilesPermutation, solutions, cfg, discardAbove);

                allSolutions.addAll(solutions);
                sort(allSolutions, cfg);

                if (solutions.get(0).getNoFitTiles().size() == 0) {
                    // TODO: To break or not to break - if not try all permutations and then choose best
                    break;
                }

                logger.info("nbrCuts[{}] maxDepth[{}] nbrNoFitTiles[{}]", solutions.get(0).getNbrCuts(), solutions.get(0).getMaxDepth(), solutions.get(0).getNoFitTiles().size());
                RunningTasks.Task task = runningTasks.getTask(cfg.getTaskId());
                if (task != null) {
                    task.setSolution((new TilingResponseDTOBuilder()).setSolutions(allSolutions.get(0)).setInfo(null).build());
                } else {
                    logger.info("Tried to update solution for a canceled task. Stopping...");
                    break;
                }
            }

            sort(allSolutions, cfg);

            if (runningTasks.getTask(cfg.getTaskId()) == null || (allSolutions.get(0).getNoFitTiles().size() == 0 && startWith > 3)) {
                break;
            }
            else if(allSolutions.get(0).getNoFitTiles().size() == 0 && !cfg.getForceOneBaseTile()) {
                startWith++;
            }
        }

        long elapsedTime = System.currentTimeMillis() - startTime;


        logger.info("Elapsed: " + elapsedTime + " ms");
        allSolutions.get(0).setElapsedTime(elapsedTime);

        runningTasks.removeTask(cfg.getTaskId());

        return (new TilingResponseDTOBuilder()).setSolutions(allSolutions.get(0)).setInfo(null).build();
    }


    /**
     * Calculate all the possibilities for fitting the specified tile into the root node.
     *
     * @param tileToAdd The dimensions of the tile to be fitted.
     * @param mosaic The root node from witch to work when considering the possibilities for fitting the tile.
     * @return A list of root nodes of all the possibilities for fitting the tile.
     */
    private void fitTile(TileDimensions tileToAdd, Mosaic mosaic, List<Mosaic> possibilities, int cutThickness) {

        List<Cut> newCuts;
        List<TileNode> candidates = new ArrayList<>();

        findCandidates(tileToAdd.getWidth(), tileToAdd.getHeight(), mosaic.getRootTileNode(), candidates);

        for (TileNode candidate : candidates) {

            // No need to split, tile has the exact required dimensions.
            if (candidate.getWidth() == tileToAdd.getWidth() && candidate.getHeight() == tileToAdd.getHeight()) {
                // TODO: Is the copy really needed?
                TileNode possibilitiy = copy(mosaic.getRootTileNode(), candidate);
                TileNode candidateCopy = possibilitiy.findTile(candidate);

                candidateCopy.setExternalId(tileToAdd.getId());
                candidateCopy.setFinal(true);


                Mosaic newMosaic = new Mosaic(possibilitiy);
                newMosaic.getCuts().addAll(mosaic.getCuts());
                possibilities.add(newMosaic);
                continue;
            }

            // Consider possibilities by splitting first horizontally
            TileNode possibilitiy = copy(mosaic.getRootTileNode(), candidate);
            TileNode candidateCopy = possibilitiy.findTile(candidate);

            newCuts = splitHV(candidateCopy, tileToAdd, cutThickness);

            Mosaic newMosaic = new Mosaic(possibilitiy);
            newMosaic.getCuts().addAll(mosaic.getCuts());
            newMosaic.getCuts().addAll(newCuts);
            possibilities.add(newMosaic);

            // Second split result would be the same
            if (candidate.getWidth() == tileToAdd.getWidth() || candidate.getHeight() == tileToAdd.getHeight()) {
                continue;
            }

            // Consider now possibilities by splitting vertically
            TileNode possibilitiy2 = copy(mosaic.getRootTileNode(), candidate);
            TileNode candidateCopy2 = possibilitiy2.findTile(candidate);

            newCuts = splitVH(candidateCopy2, tileToAdd, cutThickness);

            Mosaic newMosaic2 = new Mosaic(possibilitiy2);
            newMosaic2.getCuts().addAll(mosaic.getCuts());
            newMosaic2.getCuts().addAll(newCuts);
            possibilities.add(newMosaic2);
        }
    }

    /**
     * Adds the specified tile to the provided root node.
     *
     * @param tileDimensions Dimensions of the tile to be added.
     * @param mosaic The root node to add the tile to.
     * @return The root nodes of every possibility.
     */
    private void add(TileDimensions tileDimensions, Mosaic mosaic, List<Mosaic> possibilities, int cutThickness, boolean allowRotation) {

        // Calculate possibilities with tile as is
        fitTile(tileDimensions, mosaic, possibilities, cutThickness);

        // Consider possibilities after rotating the tile 90º
        if (allowRotation && !tileDimensions.isSquare()) {
            tileDimensions = tileDimensions.rotate90();
            fitTile(tileDimensions, mosaic, possibilities, cutThickness);
        }
    }

    /**
     * Splits the specified node to fit a tile with specified dimensions.
     * Split horizontally first.
     *
     * @param tileNode The tile node to be splitted.
     */
    private static List<Cut> splitHV(TileNode tileNode, TileDimensions tileDimensions, int cutThickness) {

        List<Cut> cuts = new ArrayList<>();

        // Check if tile needs to be split horizontally.
        if (tileNode.getWidth() > tileDimensions.getWidth()) {
            cuts.add(splitHorizontally(tileNode, tileDimensions.getWidth(), cutThickness));

            // Check if tile needs to be split vertically.
            if (tileNode.getHeight() > tileDimensions.getHeight()) {
                // Vertically split the tile resulting from the horizontal split.
                cuts.add(splitVertically(tileNode.getChild1(), tileDimensions.getHeight(), cutThickness, tileDimensions.getId()));
                // 1st child from vertical split of the 1st child from the horizontal split is the final tile.
                tileNode.getChild1().getChild1().setFinal(true);
            } else {
                // No need to split vertically, the 1st child from horizontal split will be the final tile.
                tileNode.getChild1().setFinal(true);
                tileNode.getChild1().setExternalId(tileDimensions.getId());
            }

        } else {
            // No need to split horizontally, just split vertically and the 1st child will be the final tile.
            cuts.add(splitVertically(tileNode, tileDimensions.getHeight(), cutThickness, tileDimensions.getId()));
            tileNode.getChild1().setFinal(true);
        }

        return cuts;
    }

    /**
     * Splits the specified node to fit a tile with specified dimensions.
     * Split vertically first.
     *
     * @param tileNode The tile node to be splitted.
     */
    private static List<Cut> splitVH(TileNode tileNode, TileDimensions tileDimensions, int cutThickness) {

        List<Cut> cuts = new ArrayList<>();

        // Check if tile needs to be split vertically.
        if (tileNode.getHeight() > tileDimensions.getHeight()) {
            cuts.add(splitVertically(tileNode, tileDimensions.getHeight(), cutThickness));

            // Check if tile needs to be split horizontally.
            if (tileNode.getWidth() > tileDimensions.getWidth()) {
                // Vertically split the two tiles resulting from the horizontal split.
                cuts.add(splitHorizontally(tileNode.getChild1(), tileDimensions.getWidth(), cutThickness, tileDimensions.getId()));
                // 1st child from vertical split of the 1st child from the horizontal split is the final tile.
                tileNode.getChild1().getChild1().setFinal(true);
            } else {
                // No need to split vertically, the 1st child from horizontal split will be the final tile.
                tileNode.getChild1().setFinal(true);
                tileNode.getChild1().setExternalId(tileDimensions.getId());
            }

        } else {
            // No need to split horizontally, just split vertically and the 1st child will be the final tile.
            cuts.add(splitHorizontally(tileNode, tileDimensions.getWidth(), cutThickness, tileDimensions.getId()));
            tileNode.getChild1().setFinal(true);
        }

        return cuts;
    }

    /**
     * Finds candidate tiles to fit the tile of the specified dimensions
     *
     * @param w Width of the tile to fit.
     * @param h Height of the tile to fit.
     * @param rootTileNode Root tile node to traverse for finding empty tile candidates.
     * @param candidates List to append the candidate tile nodes.
     */
    private static void findCandidates(int w, int h, TileNode rootTileNode, List<TileNode> candidates) {

        // If no tile or this tile is final, no candidate here.
        if (rootTileNode == null || rootTileNode.isFinal()) {
            return;
        }

        // If required dimensions are not fulfilled by this tile,
        // they will also not be fulfilled by its children, no candidate here.
        if(rootTileNode.getWidth() < w || rootTileNode.getHeight() < h) {
            return;
        }

        if (rootTileNode.getChild1() == null && rootTileNode.getChild2() == null) {
            // This tile has no children, see if it fits for candidate.
            if(rootTileNode.getWidth() >= w && rootTileNode.getHeight() >= h) {
                candidates.add(rootTileNode);
            }
        } else {
            // This tile has children, check recursively if any of them is a possible candidate.
            if (rootTileNode.getChild1() != null) {
                findCandidates(w, h, rootTileNode.getChild1(), candidates);
            }
            if (rootTileNode.getChild2() != null) {
                findCandidates(w, h, rootTileNode.getChild2(), candidates);
            }
        }
    }

    /**
     * Recursively copy specified note until maxLevel.
     * Tile node specified has maximum level will also be included in the copy.
     *
     * @param tileNode
     * @param maxLevel Copy until this node.
     * @return The copy root node.
     */
    private static TileNode copy(TileNode tileNode, TileNode maxLevel) {
        TileNode copy = new TileNode(tileNode);
        copyChildren(tileNode, copy, maxLevel);
        return copy;
    }

    /**
     * Recursively copy node children until maxLevel node, maxLevel included.
     * Root node is not copied.
     *
     * @param src Source tile node.
     * @param dst Destiny tile node.
     * @param maxLevel Copy until this node.
     */
    private static void copyChildren(TileNode src, TileNode dst, TileNode maxLevel) {

        if (src == maxLevel) {
            return;
        }

        if (src.getChild1() != null) {
            // TODO:
            //if (src.getChild1() instanceof ImmutableTileNode) {
            //    dst.setChild1(src.getChild1());
            //}else {
                dst.setChild1(new TileNode(src.getChild1()));
            //}
            copyChildren(src.getChild1(), dst.getChild1(), maxLevel);
        }

        if (src.getChild2() != null) {
            dst.setChild2(new TileNode(src.getChild2()));
            copyChildren(src.getChild2(), dst.getChild2(), maxLevel);
        }
    }

    /**
     * Horizontally split the specified tile node.
     * Resulting 1st child will have the specified width.
     *
     * @param tileNode Tile node to split.
     * @param w Desired width for the 1st child.
     */
    private static Cut splitHorizontally(TileNode tileNode, int w, int cutThickness) {
        return splitHorizontally(tileNode, w, cutThickness, 999);
    }

    /**
     * Horizontally split the specified tile node.
     * Resulting 1st child will have the specified width.
     *
     * @param tileNode Tile node to split.
     * @param w Desired width for the 1st child.
     */
    private static Cut splitHorizontally(TileNode tileNode, int w, int cutThickness, int id) {

        int originalWidth = tileNode.getWidth();
        int originalHeight = tileNode.getHeight();

        if (tileNode == null) {
            return null;
        }

        TileNode child1 = new TileNode(
                tileNode.getX1(),
                tileNode.getX1() + w,
                tileNode.getY1(),
                tileNode.getY2());
        child1.setExternalId(id);
        if (child1.getArea() > 0) {
            tileNode.setChild1(child1);
        }

        TileNode child2 = new TileNode(
                tileNode.getX1() + w + cutThickness,
                tileNode.getX2(),
                tileNode.getY1(),
                tileNode.getY2());
        if (child2.getArea() > 0) {
            tileNode.setChild2(child2);
        }

        Cut cut = new Cut.Builder()
                .setX1(tileNode.getX1() + w)
                .setY1(tileNode.getY1())
                .setX2(tileNode.getX1() + w)
                .setY2(tileNode.getY2())
                .setOriginalWidth(originalWidth)
                .setOriginalHeight(originalHeight)
                .setHorizontal(true)
                .setCutCoords(w)
                .setOriginalTileId(tileNode.getId())
                .setChild1TileId(child1.getId())
                .setChild2TileId(child2.getId())
                .build();

        return cut;
    }

    /**
     * Vertically split the specified tile node.
     * Resulting 1st child will have the specified height.
     *
     * @param tileNode Tile node to split.
     * @param h Desired height for the 1st child.
     */
    private static Cut splitVertically(TileNode tileNode, int h, int cutThickness) {
        return splitVertically(tileNode, h, cutThickness, 999);
    }

    /**
     * Vertically split the specified tile node.
     * Resulting 1st child will have the specified height.
     *
     * @param tileNode Tile node to split.
     * @param h Desired height for the 1st child.
     */
    private static Cut splitVertically(TileNode tileNode, int h, int cutThickness, int id) {

        int originalWidth = tileNode.getWidth();
        int originalHeight = tileNode.getHeight();

        if (tileNode == null) {
            return null;
        }

        TileNode child1 = new TileNode(
                tileNode.getX1(),
                tileNode.getX2(),
                tileNode.getY1(),
                tileNode.getY1() + h);
        child1.setExternalId(id);
        if (child1.getArea() > 0) {
            tileNode.setChild1(child1);
        }

        TileNode child2 = new TileNode(
                tileNode.getX1(),
                tileNode.getX2(),
                tileNode.getY1() + h + cutThickness,
                tileNode.getY2());
        if (child2.getArea() > 0) {
            tileNode.setChild2(child2);
        }

        Cut cut = new Cut.Builder()
                .setX1(tileNode.getX1())
                .setY1(tileNode.getY1() + h)
                .setX2(tileNode.getX2())
                .setY2(tileNode.getY1() + h)
                .setOriginalWidth(originalWidth)
                .setOriginalHeight(originalHeight)
                .setHorizontal(true)
                .setCutCoords(h)
                .setOriginalTileId(tileNode.getId())
                .setChild1TileId(child1.getId())
                .setChild2TileId(child2.getId())
                .build();

        return cut;
    }
}
