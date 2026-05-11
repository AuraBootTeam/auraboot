package com.auraboot.module.aps.engine;

import com.auraboot.module.aps.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Genetic Algorithm scheduling strategy for APS.
 * Optimizes a weighted objective of makespan, tardiness, and idle time
 * using tournament selection, order crossover (OX), and swap mutation.
 */
@Component("genetic")
@Slf4j
public class GeneticAlgorithmStrategy implements SchedulingStrategy {

    // GA parameters
    private static final int POPULATION_SIZE = 100;
    private static final int MAX_GENERATIONS = 500;
    private static final double CROSSOVER_RATE = 0.8;
    private static final double MUTATION_RATE = 0.05;
    private static final int ELITISM_COUNT = 5;
    private static final int STAGNATION_LIMIT = 50;
    private static final long TIMEOUT_MS = 30_000;
    private static final int TOURNAMENT_K = 3;

    // Fitness weights (lower is better)
    private static final double W_MAKESPAN = 0.5;
    private static final double W_TARDINESS = 0.3;
    private static final double W_IDLE = 0.2;

    private final Random random = new Random(42);

    @Override
    public String name() {
        return "genetic";
    }

    @Override
    public String description() {
        return "Genetic Algorithm — optimizes makespan, tardiness, and idle time";
    }

    @Override
    public ScheduleResult schedule(ScheduleRequest request) {
        List<ScheduleJob> jobs = request.getJobs();
        int n = jobs.size();

        if (n == 0) {
            return ScheduleResult.builder()
                    .strategy(name())
                    .operations(List.of())
                    .conflicts(List.of())
                    .resourceUtilization(Map.of())
                    .build();
        }

        // For single job, no need for GA — just decode directly
        if (n == 1) {
            int[] trivial = {0};
            DecodedSchedule decoded = decode(trivial, request);
            return buildResult(decoded, request);
        }

        // 1. Initialize population
        List<Chromosome> population = initializePopulation(n);

        // 2. Evaluate initial fitness
        for (Chromosome c : population) {
            c.fitness = evaluateFitness(c.genes, request);
        }

        // 3. Evolution loop
        Chromosome bestEver = getBest(population);
        int stagnationCount = 0;
        long startTime = System.currentTimeMillis();

        for (int gen = 0; gen < MAX_GENERATIONS; gen++) {
            // Check timeout
            if (System.currentTimeMillis() - startTime > TIMEOUT_MS) {
                log.debug("GA terminated by timeout at generation {}", gen);
                break;
            }

            // Check stagnation
            if (stagnationCount >= STAGNATION_LIMIT) {
                log.debug("GA terminated by stagnation at generation {}", gen);
                break;
            }

            // Sort population by fitness (ascending — lower is better)
            population.sort(Comparator.comparingDouble(c -> c.fitness));

            // Elitism: keep top ELITISM_COUNT
            List<Chromosome> nextGen = new ArrayList<>();
            for (int i = 0; i < Math.min(ELITISM_COUNT, population.size()); i++) {
                nextGen.add(population.get(i).copy());
            }

            // Fill rest of next generation
            while (nextGen.size() < POPULATION_SIZE) {
                Chromosome parent1 = tournamentSelect(population);
                Chromosome parent2 = tournamentSelect(population);

                Chromosome child;
                if (random.nextDouble() < CROSSOVER_RATE) {
                    child = orderCrossover(parent1, parent2);
                } else {
                    child = parent1.copy();
                }

                if (random.nextDouble() < MUTATION_RATE) {
                    child = swapMutate(child);
                }

                child.fitness = evaluateFitness(child.genes, request);
                nextGen.add(child);
            }

            population = nextGen;

            // Track best
            Chromosome currentBest = getBest(population);
            if (currentBest.fitness < bestEver.fitness) {
                bestEver = currentBest.copy();
                stagnationCount = 0;
            } else {
                stagnationCount++;
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.debug("GA completed: fitness={}, elapsed={}ms", bestEver.fitness, elapsed);

        // 4. Decode best chromosome
        DecodedSchedule decoded = decode(bestEver.genes, request);
        return buildResult(decoded, request);
    }

    // ==================== Internal classes ====================

    /**
     * A chromosome: an array of job indices (permutation) and its fitness score.
     */
    private static class Chromosome {
        int[] genes;
        double fitness;

        Chromosome(int[] genes) {
            this.genes = genes;
            this.fitness = Double.MAX_VALUE;
        }

        Chromosome copy() {
            Chromosome c = new Chromosome(Arrays.copyOf(genes, genes.length));
            c.fitness = this.fitness;
            return c;
        }
    }

    /**
     * Decoded schedule result: operations, conflicts, utilization.
     */
    private static class DecodedSchedule {
        List<ScheduledOperation> operations;
        List<ScheduleConflict> conflicts;
        Map<Long, Double> utilization;
        LocalDateTime earliestCompletion;
        long makespanMinutes;
        long totalTardinessMinutes;
        long totalIdleMinutes;
    }

    // ==================== GA operations ====================

    private List<Chromosome> initializePopulation(int n) {
        List<Chromosome> population = new ArrayList<>(POPULATION_SIZE);
        for (int i = 0; i < POPULATION_SIZE; i++) {
            int[] perm = new int[n];
            for (int j = 0; j < n; j++) {
                perm[j] = j;
            }
            // Fisher-Yates shuffle
            for (int j = n - 1; j > 0; j--) {
                int k = random.nextInt(j + 1);
                int temp = perm[j];
                perm[j] = perm[k];
                perm[k] = temp;
            }
            population.add(new Chromosome(perm));
        }
        return population;
    }

    private Chromosome tournamentSelect(List<Chromosome> population) {
        Chromosome best = null;
        for (int i = 0; i < TOURNAMENT_K; i++) {
            Chromosome candidate = population.get(random.nextInt(population.size()));
            if (best == null || candidate.fitness < best.fitness) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Order Crossover (OX): preserves a substring from parent1 and fills remaining
     * positions with the order from parent2.
     */
    private Chromosome orderCrossover(Chromosome p1, Chromosome p2) {
        int n = p1.genes.length;
        if (n <= 1) return p1.copy();

        int[] child = new int[n];
        Arrays.fill(child, -1);

        // Select crossover segment
        int start = random.nextInt(n);
        int end = random.nextInt(n);
        if (start > end) {
            int temp = start;
            start = end;
            end = temp;
        }

        // Copy segment from parent1
        Set<Integer> used = new HashSet<>();
        for (int i = start; i <= end; i++) {
            child[i] = p1.genes[i];
            used.add(p1.genes[i]);
        }

        // Fill remaining from parent2 in order
        int pos = (end + 1) % n;
        for (int i = 0; i < n; i++) {
            int idx = (end + 1 + i) % n;
            int gene = p2.genes[idx];
            if (!used.contains(gene)) {
                child[pos] = gene;
                pos = (pos + 1) % n;
            }
        }

        return new Chromosome(child);
    }

    /**
     * Swap mutation: swap two random positions.
     */
    private Chromosome swapMutate(Chromosome c) {
        int n = c.genes.length;
        if (n <= 1) return c;

        Chromosome mutated = c.copy();
        int i = random.nextInt(n);
        int j = random.nextInt(n - 1);
        if (j >= i) {
            j++;
        }
        int temp = mutated.genes[i];
        mutated.genes[i] = mutated.genes[j];
        mutated.genes[j] = temp;
        return mutated;
    }

    private Chromosome getBest(List<Chromosome> population) {
        return population.stream()
                .min(Comparator.comparingDouble(c -> c.fitness))
                .orElseThrow();
    }

    // ==================== Decoding & Fitness ====================

    private double evaluateFitness(int[] genes, ScheduleRequest request) {
        DecodedSchedule decoded = decode(genes, request);
        return W_MAKESPAN * decoded.makespanMinutes
                + W_TARDINESS * decoded.totalTardinessMinutes
                + W_IDLE * decoded.totalIdleMinutes;
    }

    /**
     * Decode a job permutation into a schedule.
     * Assigns jobs to compatible resources in permutation order,
     * finding the earliest available slot on each compatible resource.
     */
    private DecodedSchedule decode(int[] genes, ScheduleRequest request) {
        List<ScheduleJob> jobs = request.getJobs();
        List<ResourceInfo> allResources = request.getResources();
        Map<String, Integer> setups = request.getSetupTimes();
        LocalDateTime start = request.getScheduleStart() != null
                ? request.getScheduleStart() : LocalDateTime.now();

        // Track resource availability and last product per resource
        Map<Long, LocalDateTime> resourceAvail = new HashMap<>();
        Map<Long, Long> lastProductOnResource = new HashMap<>();
        for (ResourceInfo res : allResources) {
            resourceAvail.put(res.getId(), start);
        }

        // Index resources by type for fast lookup
        Map<String, List<ResourceInfo>> resourcesByType = allResources.stream()
                .collect(Collectors.groupingBy(ResourceInfo::getType));

        List<ScheduledOperation> operations = new ArrayList<>();
        List<ScheduleConflict> conflicts = new ArrayList<>();

        for (int geneIdx : genes) {
            ScheduleJob job = jobs.get(geneIdx);

            // Find compatible resources
            List<ResourceInfo> candidates;
            if (job.getRequiredResourceId() != null) {
                candidates = allResources.stream()
                        .filter(r -> r.getId().equals(job.getRequiredResourceId()))
                        .collect(Collectors.toList());
            } else {
                candidates = resourcesByType.getOrDefault(job.getRequiredResourceType(), List.of());
            }

            if (candidates.isEmpty()) {
                conflicts.add(ScheduleConflict.builder()
                        .jobId(job.getId())
                        .reason("No available resource of type " + job.getRequiredResourceType())
                        .build());
                continue;
            }

            // Find best resource: earliest available after setup
            ResourceInfo bestResource = null;
            LocalDateTime bestStart = null;
            int bestSetup = 0;

            for (ResourceInfo res : candidates) {
                LocalDateTime avail = resourceAvail.getOrDefault(res.getId(), start);
                int setupTime = 0;

                // Calculate setup time if product changes
                Long lastProduct = lastProductOnResource.get(res.getId());
                if (lastProduct != null && job.getProductId() != null
                        && !lastProduct.equals(job.getProductId()) && setups != null) {
                    String key = lastProduct + "-" + job.getProductId();
                    setupTime = setups.getOrDefault(key, 0);
                }

                LocalDateTime jobStart = avail.plusMinutes(setupTime);

                if (bestStart == null || jobStart.isBefore(bestStart)) {
                    bestResource = res;
                    bestStart = jobStart;
                    bestSetup = setupTime;
                }
            }

            LocalDateTime jobEnd = bestStart.plusMinutes(job.getProcessingTimeMin());

            operations.add(ScheduledOperation.builder()
                    .jobId(job.getId())
                    .jobCode(job.getCode())
                    .productName(job.getProductName())
                    .operationName(job.getOperationName())
                    .resourceId(bestResource.getId())
                    .resourceName(bestResource.getName())
                    .startTime(bestStart)
                    .endTime(jobEnd)
                    .setupTimeMin(bestSetup)
                    .processingTimeMin(job.getProcessingTimeMin())
                    .build());

            resourceAvail.put(bestResource.getId(), jobEnd);
            if (job.getProductId() != null) {
                lastProductOnResource.put(bestResource.getId(), job.getProductId());
            }

            // Check due date
            if (job.getDueDate() != null && jobEnd.isAfter(job.getDueDate())) {
                conflicts.add(ScheduleConflict.builder()
                        .jobId(job.getId())
                        .reason("Past due")
                        .requestedBy(job.getDueDate())
                        .achievableBy(jobEnd)
                        .build());
            }
        }

        // Calculate metrics
        DecodedSchedule decoded = new DecodedSchedule();
        decoded.operations = operations;
        decoded.conflicts = conflicts;

        if (operations.isEmpty()) {
            decoded.makespanMinutes = 0;
            decoded.totalTardinessMinutes = 0;
            decoded.totalIdleMinutes = 0;
            decoded.earliestCompletion = start;
        } else {
            LocalDateTime latestEnd = operations.stream()
                    .map(ScheduledOperation::getEndTime)
                    .max(LocalDateTime::compareTo)
                    .orElse(start);

            decoded.earliestCompletion = latestEnd;
            decoded.makespanMinutes = ChronoUnit.MINUTES.between(start, latestEnd);

            // Total tardiness
            long tardiness = 0;
            for (int geneIdx : genes) {
                ScheduleJob job = jobs.get(geneIdx);
                if (job.getDueDate() != null) {
                    Optional<ScheduledOperation> op = operations.stream()
                            .filter(o -> o.getJobId().equals(job.getId()))
                            .findFirst();
                    if (op.isPresent() && op.get().getEndTime().isAfter(job.getDueDate())) {
                        tardiness += ChronoUnit.MINUTES.between(job.getDueDate(), op.get().getEndTime());
                    }
                }
            }
            decoded.totalTardinessMinutes = tardiness;

            // Total idle time per resource
            long totalBusyMinutes = operations.stream()
                    .mapToLong(op -> op.getSetupTimeMin() + op.getProcessingTimeMin())
                    .sum();
            long totalSlotMinutes = 0;
            for (ResourceInfo res : allResources) {
                LocalDateTime resEnd = resourceAvail.getOrDefault(res.getId(), start);
                long resMinutes = ChronoUnit.MINUTES.between(start, resEnd);
                if (resMinutes > 0) {
                    totalSlotMinutes += resMinutes;
                }
            }
            decoded.totalIdleMinutes = Math.max(0, totalSlotMinutes - totalBusyMinutes);
        }

        // Utilization
        decoded.utilization = calculateUtilization(operations, allResources, start);

        return decoded;
    }

    private ScheduleResult buildResult(DecodedSchedule decoded, ScheduleRequest request) {
        return ScheduleResult.builder()
                .strategy(name())
                .operations(decoded.operations)
                .conflicts(decoded.conflicts)
                .resourceUtilization(decoded.utilization)
                .earliestCompletion(decoded.earliestCompletion)
                .build();
    }

    private Map<Long, Double> calculateUtilization(List<ScheduledOperation> operations,
                                                     List<ResourceInfo> resources,
                                                     LocalDateTime start) {
        Map<Long, Double> utilization = new HashMap<>();
        if (operations.isEmpty()) return utilization;

        LocalDateTime end = operations.stream()
                .map(ScheduledOperation::getEndTime)
                .max(LocalDateTime::compareTo)
                .orElse(start);
        long totalMinutes = ChronoUnit.MINUTES.between(start, end);
        if (totalMinutes <= 0) return utilization;

        for (ResourceInfo res : resources) {
            long busyMinutes = operations.stream()
                    .filter(op -> op.getResourceId().equals(res.getId()))
                    .mapToLong(op -> op.getSetupTimeMin() + op.getProcessingTimeMin())
                    .sum();
            utilization.put(res.getId(), Math.round(busyMinutes * 1000.0 / totalMinutes) / 10.0);
        }
        return utilization;
    }
}
