package tourGuide.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tourGuide.helper.InternalTestHelper;
import tourGuide.model.*;
import tourGuide.proxy.GpsMicroServiceProxy;
import tourGuide.proxy.RewardCentralMicroServiceProxy;
import tourGuide.proxy.TripPricerMicroServiceProxy;
import tourGuide.tracker.Tracker;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class TourGuideService {
    private Logger logger = LoggerFactory.getLogger(TourGuideService.class);
    private final GpsMicroServiceProxy gpsUtil;
    private final RewardsService rewardsService;
    private final TripPricerMicroServiceProxy tripPricer;
    private final RewardCentralMicroServiceProxy rewardCentral;
    public final Tracker tracker;
    boolean testMode = true;

    public TourGuideService(GpsMicroServiceProxy gpsUtil, RewardsService rewardsService, TripPricerMicroServiceProxy tripPricer, RewardCentralMicroServiceProxy rewardCentral) {
        this.gpsUtil = gpsUtil;
        this.rewardsService = rewardsService;
        this.tripPricer = tripPricer;
        this.rewardCentral = rewardCentral;

        if (testMode) {
            logger.info("TestMode enabled");
            logger.debug("Initializing users");
            initializeInternalUsers();
            logger.debug("Finished initializing users");
        }
        tracker = new Tracker(this);
        addShutDownHook();
    }

    public List<UserReward> getUserRewards(User user) {
        return user.getUserRewards();
    }

    public VisitedLocation getUserLocation(User user) {
        VisitedLocation visitedLocation = (user.getVisitedLocations().size() > 0) ?
                user.getLastVisitedLocation() :
                trackUserLocation(user);
        return visitedLocation;
    }

    public User getUser(String userName) {
        return internalUserMap.get(userName);
    }

    public List<User> getAllUsers() {
        return internalUserMap.values().stream().collect(Collectors.toList());
    }

    public void addUser(User user) {
        if (!internalUserMap.containsKey(user.getUserName())) {
            internalUserMap.put(user.getUserName(), user);
        }
    }

    /**
     * Récupère le meilleur forfait de voyage en fonctions des preférences l'utilisateur.
     * @param user l'utilisateur en question.
     * @return les providers personnalisés.
     */
    public List<Provider> getTripDeals(User user) {
        int cumulativeRewardPoints = user.getUserRewards().stream().mapToInt(i -> i.getRewardPoints()).sum();
        List<Provider> providers = tripPricer.getPrice(tripPricerApiKey, user.getUserId(), user.getUserPreferences().getNumberOfAdults(),
                user.getUserPreferences().getNumberOfChildren(), user.getUserPreferences().getTripDuration(), cumulativeRewardPoints);
        user.setTripDeals(providers);
        return providers;
    }

    /**
     * Récupère la dernière location de l'utilisateur et l'ajoute à ses données.
     * @param user l'utilisateur en question.
     * @return la dernière position visitée.
     */
    public VisitedLocation trackUserLocation(User user) {
        VisitedLocation visitedLocation = gpsUtil.getUserLocation(user.getUserId());
        user.addToVisitedLocations(visitedLocation);
        rewardsService.calculateRewards(user);
        return visitedLocation;
    }

    /**
     * Récupère les 5 attractions les plus proches de la dernière position visitée de l'utilisateur.
     * @param visitedLocation la dernière position de l'utilisateur.
     * @return les 5 attractions les plus proches.
     */
    public CloserAttractions getNearByAttractions(VisitedLocation visitedLocation) {
        List<AttractionDetails> attractionDetailsList = new ArrayList<>();
        for (Attraction attraction : gpsUtil.getAttractions()) {

            double distanceOfCurrentAttraction = rewardsService.getDistance(new Location(attraction.latitude, attraction.longitude), visitedLocation.location);

            AttractionDetails attractionDetails = new AttractionDetails(
                    attraction.attractionName,
                    new Location(attraction.latitude, attraction.longitude),
                    distanceOfCurrentAttraction,
                    rewardCentral.getAttractionsRewardPoints(attraction.attractionId, visitedLocation.userId));

            attractionDetailsList.add(attractionDetails);

        }
        attractionDetailsList.sort((Comparator.comparingDouble(AttractionDetails::getTouristAttractionDistanceToUser)));
        attractionDetailsList = attractionDetailsList.subList(0, 5);

        return new CloserAttractions(visitedLocation.location, attractionDetailsList);
    }

    private void addShutDownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                tracker.stopTracking();
            }
        });
    }

    /**********************************************************************************
     *
     * Methods Below: For Internal Testing
     *
     **********************************************************************************/
    private static final String tripPricerApiKey = "test-server-api-key";
    // Database connection will be used for external users, but for testing purposes internal users are provided and stored in memory
    private final Map<String, User> internalUserMap = new HashMap<>();

    private void initializeInternalUsers() {
        IntStream.range(0, InternalTestHelper.getInternalUserNumber()).forEach(i -> {
            String userName = "internalUser" + i;
            String phone = "000";
            String email = userName + "@tourGuide.com";
            User user = new User(UUID.randomUUID(), userName, phone, email);
            generateUserLocationHistory(user);

            internalUserMap.put(userName, user);
        });
        logger.debug("Created " + InternalTestHelper.getInternalUserNumber() + " internal test users.");
    }

    private void generateUserLocationHistory(User user) {
        IntStream.range(0, 3).forEach(i -> {
            user.addToVisitedLocations(new VisitedLocation(user.getUserId(), new Location(generateRandomLatitude(), generateRandomLongitude()), getRandomTime()));
        });
    }

    private double generateRandomLongitude() {
        double leftLimit = -180;
        double rightLimit = 180;
        return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
    }

    private double generateRandomLatitude() {
        double leftLimit = -85.05112878;
        double rightLimit = 85.05112878;
        return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
    }

    private Date getRandomTime() {
        LocalDateTime localDateTime = LocalDateTime.now().minusDays(new Random().nextInt(30));
        return Date.from(localDateTime.toInstant(ZoneOffset.UTC));
    }

}
