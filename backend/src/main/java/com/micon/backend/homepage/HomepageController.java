package com.micon.backend.homepage;

import com.micon.backend.song.api.PageResponse;
import com.micon.backend.song.api.SongResponse;
import com.micon.backend.song.service.SongService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 운영 서버의 곡 카탈로그를 사람이 확인할 수 있는 HTML 화면으로 제공한다. */
@Validated
@Controller
@RequestMapping({"/homepage", "/v1/homepage"})
public class HomepageController {

	private final SongService songService;
	private final HomepageSongRepository homepageSongRepository;

	public HomepageController(SongService songService, HomepageSongRepository homepageSongRepository) {
		this.songService = songService;
		this.homepageSongRepository = homepageSongRepository;
	}

	/** 검색 조건과 페이지 번호에 맞는 실제 DB 곡 목록을 Thymeleaf 화면에 전달한다. */
	@GetMapping({"", "/songs"})
	public String songs(
		@RequestParam(defaultValue = "") String query,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "30") @Min(1) @Max(100) int size,
		Model model
	) {
		PageResponse<SongResponse.Summary> result = songService.search(query, page, size);
		List<Long> songIds = result.content().stream().map(SongResponse.Summary::songId).toList();
		Map<Long, HomepageSongStatus> statuses = homepageSongRepository.findStatuses(songIds);
		model.addAttribute("songs", result.content().stream()
			.map(song -> HomepageSongView.from(song, statuses.getOrDefault(song.songId(),
				HomepageSongStatus.missing(song.songId()))))
			.toList());
		model.addAttribute("query", query);
		model.addAttribute("page", result.page());
		model.addAttribute("size", result.size());
		model.addAttribute("totalElements", result.totalElements());
		model.addAttribute("totalPages", result.totalPages());
		model.addAttribute("hasPrevious", result.page() > 0);
		model.addAttribute("previousPage", Math.max(0, result.page() - 1));
		model.addAttribute("hasNext", result.page() + 1 < result.totalPages());
		model.addAttribute("nextPage", result.page() + 1);
		return "homepage/songs";
	}
}
