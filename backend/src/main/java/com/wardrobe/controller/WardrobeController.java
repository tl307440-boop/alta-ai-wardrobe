package com.wardrobe.controller;

import com.wardrobe.common.Result;
import com.wardrobe.model.dto.WardrobeDto;
import com.wardrobe.security.UserContextHolder;
import com.wardrobe.service.WardrobeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/wardrobe/items")
@RequiredArgsConstructor
public class WardrobeController {
    private final WardrobeService wardrobeService;

    @GetMapping
    public Result<List<WardrobeDto.ItemVo>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String color,
            @RequestParam(required = false) String season,
            @RequestParam(required = false) String keyword) {
        return Result.ok(wardrobeService.listItems(UserContextHolder.getUserId(), category, color, season, keyword));
    }

    @PostMapping
    public Result<WardrobeDto.ItemVo> create(@Valid @RequestBody WardrobeDto.CreateItemRequest req) {
        return Result.ok(wardrobeService.createItem(UserContextHolder.getUserId(), req));
    }

    @GetMapping("/{id}")
    public Result<WardrobeDto.ItemDetailVo> detail(@PathVariable Long id) {
        return Result.ok(wardrobeService.getItemDetail(UserContextHolder.getUserId(), id));
    }

    @PutMapping("/{id}")
    public Result<WardrobeDto.ItemVo> update(@PathVariable Long id, @RequestBody WardrobeDto.UpdateItemRequest req) {
        return Result.ok(wardrobeService.updateItem(UserContextHolder.getUserId(), id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        wardrobeService.deleteItem(UserContextHolder.getUserId(), id);
        return Result.ok();
    }
}
