package com.liuyanzhao.sens.web.controller.api;

import com.liuyanzhao.sens.entity.Tag;
import com.liuyanzhao.sens.model.dto.JsonResult;
import com.liuyanzhao.sens.model.enums.ResponseStatusEnum;
import com.liuyanzhao.sens.service.TagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <pre>
 *     标签API
 * </pre>
 *
 * @author : saysky
 * @date : 2018/6/6
 */
@RestController
@RequestMapping(value = "/api/tags")
public class ApiTagController {

    @Autowired
    private TagService tagService;

    /**
     * 获取所有标签
     *
     * @return JsonResult
     */
    @GetMapping
    public JsonResult tags() {
        List<Tag> tags = tagService.findAllTags();
        if (null != tags && tags.size() > 0) {
            return new JsonResult(ResponseStatusEnum.SUCCESS.getCode(), ResponseStatusEnum.SUCCESS.getMsg(), tags);
        } else {
            return new JsonResult(ResponseStatusEnum.EMPTY.getCode(), ResponseStatusEnum.EMPTY.getMsg());
        }
    }

    /**
     * 获取单个标签的信息
     *
     * @param tagUrl tagUrl
     * @return JsonResult
     */
    @GetMapping(value = "/{tagUrl}")
    public JsonResult tags(@PathVariable("tagUrl") String tagUrl) {
        Tag tag = tagService.findByTagUrl(tagUrl);
        if (null != tag) {
            return new JsonResult(ResponseStatusEnum.SUCCESS.getCode(), ResponseStatusEnum.SUCCESS.getMsg(), tag);
        } else {
            return new JsonResult(ResponseStatusEnum.NOTFOUND.getCode(), ResponseStatusEnum.NOTFOUND.getMsg());
        }
    }
}
