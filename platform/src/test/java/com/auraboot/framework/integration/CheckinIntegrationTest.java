package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.inbox.dto.CheckinRequest;
import com.auraboot.framework.inbox.dto.CheckinResponse;
import com.auraboot.framework.inbox.service.CheckinService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GPS Check-in integration test.
 * Tests create, list, entity lookup, and validation.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class CheckinIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CheckinService checkinService;

    private final String testRunId = String.valueOf(System.currentTimeMillis());
    private Long firstCheckinId;

    @BeforeEach
    void setContext() {
        MetaContext.setContext(
                getTestTenant().getId(),
                getTestUser().getId(),
                getTestUser().getPid(),
                getTestUser().getUserName()
        );
    }

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    @Order(1)
    void createCheckinBasic() {
        CheckinRequest request = new CheckinRequest();
        request.setLatitude(31.2304);
        request.setLongitude(121.4737);
        request.setAddress("Shanghai, China");
        request.setNotes("Test checkin " + testRunId);

        CheckinResponse response = checkinService.checkin(request);

        assertNotNull(response);
        assertNotNull(response.getId());
        assertEquals(31.2304, response.getLatitude());
        assertEquals(121.4737, response.getLongitude());
        assertEquals("Shanghai, China", response.getAddress());
        assertNotNull(response.getCheckinTime());
        assertNull(response.getDeepLink()); // No entity linked

        firstCheckinId = response.getId();
    }

    @Test
    @Order(2)
    void createCheckinWithEntity() {
        CheckinRequest request = new CheckinRequest();
        request.setLatitude(39.9042);
        request.setLongitude(116.4074);
        request.setAddress("Beijing, China");
        request.setNotes("Customer visit " + testRunId);
        request.setModelCode("crm_account");
        request.setRecordId(42L);
        request.setPhotoPids("photo1,photo2");

        CheckinResponse response = checkinService.checkin(request);

        assertNotNull(response);
        assertEquals("crm_account", response.getModelCode());
        assertEquals(42L, response.getRecordId());
        assertEquals("photo1,photo2", response.getPhotoPids());
        assertEquals("auraboot://object/crm_account/42", response.getDeepLink());
    }

    @Test
    @Order(3)
    void createCheckinInvalidLatitude() {
        CheckinRequest request = new CheckinRequest();
        request.setLatitude(91.0);
        request.setLongitude(0.0);

        assertThrows(IllegalArgumentException.class, () -> checkinService.checkin(request));
    }

    @Test
    @Order(4)
    void createCheckinNullCoordinates() {
        CheckinRequest request = new CheckinRequest();

        assertThrows(IllegalArgumentException.class, () -> checkinService.checkin(request));
    }

    @Test
    @Order(5)
    void getRecentCheckins() {
        List<CheckinResponse> checkins = checkinService.getRecent(null, 20);

        assertNotNull(checkins);
        assertTrue(checkins.size() >= 2, "Should have at least 2 checkins from earlier tests");

        // Verify descending order (newest first)
        if (checkins.size() >= 2) {
            assertTrue(checkins.get(0).getId() > checkins.get(1).getId());
        }
    }

    @Test
    @Order(6)
    void getRecentCheckinsCursorPagination() {
        List<CheckinResponse> checkins = checkinService.getRecent(null, 1);
        assertEquals(1, checkins.size());

        // Use cursor for next page
        Long cursor = checkins.get(0).getId();
        List<CheckinResponse> nextPage = checkinService.getRecent(cursor, 10);

        assertNotNull(nextPage);
        // All IDs should be less than cursor
        for (CheckinResponse c : nextPage) {
            assertTrue(c.getId() < cursor);
        }
    }

    @Test
    @Order(7)
    void getByEntity() {
        List<CheckinResponse> checkins = checkinService.getByEntity("crm_account", 42L);

        assertNotNull(checkins);
        assertTrue(checkins.size() >= 1);
        assertEquals("crm_account", checkins.get(0).getModelCode());
        assertEquals(42L, checkins.get(0).getRecordId());
    }

    @Test
    @Order(8)
    void getByEntityNoResults() {
        List<CheckinResponse> checkins = checkinService.getByEntity("nonexistent_model", 9999L);
        assertNotNull(checkins);
        assertTrue(checkins.isEmpty());
    }
}
