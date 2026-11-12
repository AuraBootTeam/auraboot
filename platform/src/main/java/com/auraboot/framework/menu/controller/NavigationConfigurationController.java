package com.auraboot.framework.menu.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.menu.dto.NavigationConfiguration.*;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.menu.service.NavigationConfigurationService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/navigation")
@RequiredArgsConstructor
@Tag(name="Navigation configuration")
@AuthenticatedAccess("Navigation rendering uses the caller's effective menu; authoring requires menu management")
public class NavigationConfigurationController {
    private final NavigationConfigurationService configuration;
    private final MenuService menus;

    @io.swagger.v3.oas.annotations.Operation(operationId="readNavigationConfiguration")
    @GetMapping("/configuration") @RequirePermission(MetaPermission.MENU_MANAGE)
    public ApiResponse<Snapshot> read() { return ApiResponse.success(configuration.read()); }
    @io.swagger.v3.oas.annotations.Operation(operationId="createNavigationEntry")
    @PostMapping("/entries") @RequirePermission(MetaPermission.MENU_MANAGE)
    public ApiResponse<Snapshot> create(@Valid @RequestBody Write request) { return ApiResponse.success(configuration.create(request)); }
    @io.swagger.v3.oas.annotations.Operation(operationId="updateNavigationEntry")
    @PutMapping("/entries/{pid}") @RequirePermission(MetaPermission.MENU_MANAGE)
    public ApiResponse<Snapshot> update(@PathVariable String pid,@Valid @RequestBody Write request) { return ApiResponse.success(configuration.update(pid,request)); }
    @io.swagger.v3.oas.annotations.Operation(operationId="restoreNavigationEntry")
    @PostMapping("/entries/{pid}/restore") @RequirePermission(MetaPermission.MENU_MANAGE)
    public ApiResponse<Snapshot> restore(@PathVariable String pid,@Valid @RequestBody Revision request) { return ApiResponse.success(configuration.remove(pid,request.expectedRevision(),true)); }
    @io.swagger.v3.oas.annotations.Operation(operationId="removeNavigationEntry")
    @DeleteMapping("/entries/{pid}") @RequirePermission(MetaPermission.MENU_MANAGE)
    public ApiResponse<Snapshot> remove(@PathVariable String pid,@Valid @RequestBody Revision request) { return ApiResponse.success(configuration.remove(pid,request.expectedRevision(),false)); }

    @io.swagger.v3.oas.annotations.Operation(operationId="readCurrentNavigationTarget")
    @GetMapping("/entries/{pid}")
    public ApiResponse<Target> entry(@PathVariable String pid) {
        Menu found=find(menus.getUserMenuTree(MetaContext.getCurrentUserId(),MetaContext.getCurrentTenantId()),pid);
        if(found==null)throw new AccessDeniedException("Navigation entry is unavailable to the current user");
        var extension=found.getExtension();
        return ApiResponse.success(new Target(found.getPid(),found.getName(),
                String.valueOf(extension==null?"internal":extension.get("navigationKind")),
                String.valueOf(extension==null?found.getPath():extension.get("navigationTarget")),
                String.valueOf(extension==null?"same":extension.get("navigationOpening"))));
    }
    private static Menu find(List<Menu> entries,String pid) {
        for(Menu entry:entries){if(pid.equals(entry.getPid()))return entry;if(entry.getChildren()!=null){Menu found=find(entry.getChildren(),pid);if(found!=null)return found;}}
        return null;
    }
}
