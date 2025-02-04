package com.liuyanzhao.sens.web.controller.front;

import com.baomidou.mybatisplus.plugins.Page;
import com.google.common.base.Strings;
import com.liuyanzhao.sens.entity.Comment;
import com.liuyanzhao.sens.entity.Post;
import com.liuyanzhao.sens.entity.User;
import com.liuyanzhao.sens.model.dto.SensConst;
import com.liuyanzhao.sens.model.dto.JsonResult;
import com.liuyanzhao.sens.model.enums.*;
import com.liuyanzhao.sens.service.CommentService;
import com.liuyanzhao.sens.service.MailService;
import com.liuyanzhao.sens.service.PostService;
import com.liuyanzhao.sens.service.UserService;
import com.liuyanzhao.sens.utils.CommentUtil;
import com.liuyanzhao.sens.utils.OwoUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.URLUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.extra.servlet.ServletUtil;
import cn.hutool.http.HtmlUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <pre>
 *     前台评论控制器
 * </pre>
 *
 * @author : saysky
 * @date : 2018/4/26
 */
@Slf4j
@Controller
public class FrontCommentController {

    @Autowired
    private CommentService commentService;

    @Autowired
    private PostService postService;

    @Autowired
    private UserService userService;

    @Autowired
    private MailService mailService;

    /**
     * 获取文章的评论
     *
     * @param postId postId 文章编号
     * @return List
     */
    @GetMapping(value = "/getComment/{postId}", produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
    @ResponseBody
    public List<Comment> getComment(@PathVariable Long postId) {
        Post post = postService.findByPostId(postId);
        Page page = new Page(0, 999);
        Page<Comment> comments = commentService.pagingCommentsByPostAndCommentStatus(post.getPostId(), CommentStatusEnum.PUBLISHED.getCode(), page);
        return CommentUtil.getComments(comments.getRecords());
    }

    /**
     * 加载评论
     *
     * @param page 页码
     * @param post 当前文章
     * @return List
     */
    @GetMapping(value = "/loadComment")
    @ResponseBody
    public List<Comment> loadComment(@RequestParam(value = "page") Integer page,
                                     @RequestParam(value = "post") Post post) {
        Page pagination = new Page(page, 10);
        Page<Comment> comments = commentService.pagingCommentsByPostAndCommentStatus(post.getPostId(), CommentStatusEnum.PUBLISHED.getCode(), pagination);
        return comments.getRecords();
    }

    /**
     * 提交新评论
     *
     * @param comment comment实体
     * @param post    post实体
     * @param request request
     * @return JsonResult
     */
    @PostMapping(value = "/newComment")
    @ResponseBody
    public JsonResult newComment(@Valid @ModelAttribute("comment") Comment comment,
                                 BindingResult result,
                                 @ModelAttribute("post") Post post,
                                 HttpServletRequest request) {

        boolean isEmailToAdmin = true;
        boolean isEmailToParent = comment.getCommentParent() > 0;
        if (result.hasErrors()) {
            for (ObjectError error : result.getAllErrors()) {
                return new JsonResult(ResultCodeEnum.FAIL.getCode(), error.getDefaultMessage());
            }
        }
        //1.判断字数，应该小于1000字
        if (comment != null && comment.getCommentContent() != null && comment.getCommentContent().length() > 1000) {
            return new JsonResult(ResultCodeEnum.FAIL.getCode(), "评论字数太长，请删减或者分条发送！");
        }

        //2.垃圾评论过滤
        String commentContent = comment.getCommentContent();
        String rubbishWords = SensConst.OPTIONS.get(BlogPropertiesEnum.COMMENT_RUBBISH_WORDS.getProp());
        if(!Strings.isNullOrEmpty(rubbishWords)) {
            String[] arr = rubbishWords.split(",");
            for (int i = 0; i < arr.length; i++) {
                if(commentContent.indexOf(arr[i]) != -1) {
                    return new JsonResult(ResultCodeEnum.SUCCESS.getCode(), "你的评论已经提交，待博主审核之后可显示。");
                }
            }
        }

        //3.检查重复评论
        String ip = ServletUtil.getClientIP(request);
        Comment latestComment = commentService.getLastestComment(ip);
        if(latestComment != null && Objects.equals(latestComment.getCommentContent(), comment.getCommentContent())) {
            return new JsonResult(ResultCodeEnum.SUCCESS.getCode(), "评论成功！");
        }

        try {
            //2.检查文章是否存在
            Subject subject = SecurityUtils.getSubject();
            User user = (User) subject.getPrincipal();
            Comment lastComment = null;
            post = postService.findByPostId(post.getPostId());
            if (post == null) {
                return new JsonResult(ResultCodeEnum.FAIL.getCode(), "文章不存在！");
            }
            comment.setIsAdmin(0);

            //3.判断是评论还是回复
            //回复评论
            if (comment.getCommentParent() > 0) {
                lastComment = commentService.findCommentById(comment.getCommentParent());
                if (lastComment == null) {
                    return new JsonResult(ResultCodeEnum.FAIL.getCode(), "回复的评论不存在！");
                }
                comment.setAcceptUserId(lastComment.getUserId());
                comment.setPathTrace(lastComment.getPathTrace() + lastComment.getCommentId() + "/");
                String lastContent = "<a href='#comment-id-" + lastComment.getCommentId() + "'>@" + lastComment.getCommentAuthor() + "</a>";
                comment.setCommentContent(lastContent + OwoUtil.markToImg(HtmlUtil.escape(comment.getCommentContent())));
            }
            //评论
            else {
                comment.setAcceptUserId(post.getUserId());
                comment.setPathTrace("/");
                //将评论内容的字符专为安全字符
                comment.setCommentContent(OwoUtil.markToImg(HtmlUtil.escape(comment.getCommentContent())));
            }

            //4.判断是否登录
            //如果已登录
            if (subject.isAuthenticated()) {
                if (Objects.equals(post.getUserId(), user.getUserId())) {
                    comment.setIsAdmin(1);
                }
                comment.setUserId(user.getUserId());
                comment.setCommentAuthorEmail(user.getUserEmail());
                comment.setCommentAuthor(user.getUserDisplayName());
                comment.setCommentAuthorUrl(user.getUserSite());
                comment.setCommentAuthorAvatar(user.getUserAvatar());
                //如果评论的是自己的文章，不发邮件
                if (Objects.equals(user.getUserId(), post.getUserId())) {
                    isEmailToAdmin = false;
                }
                //如果回复的是自己评论，不发邮件
                if (lastComment != null && Objects.equals(user.getUserId(), lastComment.getUserId())) {
                    isEmailToParent = false;
                }
            }
            //匿名评论
            else {
                comment.setCommentAuthorEmail(HtmlUtil.escape(comment.getCommentAuthorEmail()).toLowerCase());
                comment.setCommentAuthor(HtmlUtil.escape(comment.getCommentAuthor()));
                if (StringUtils.isNotEmpty(comment.getCommentAuthorUrl())) {
                    comment.setCommentAuthorUrl(URLUtil.formatUrl(comment.getCommentAuthorUrl()));
                }
                comment.setUserId(0L);
                if (StringUtils.isNotBlank(comment.getCommentAuthorEmail())) {
                    comment.setCommentAuthorEmailMd5(SecureUtil.md5(comment.getCommentAuthorEmail()));
                }
                //如果回复的是自己评论，不发邮件
                if (lastComment != null && Objects.equals(comment.getCommentAuthorEmail(), lastComment.getCommentAuthorEmail())) {
                    isEmailToParent = false;
                }
            }
            comment.setPostId(post.getPostId());
            comment.setCommentDate(DateUtil.date());
            comment.setCommentAuthorIp(ip);


            //5.保存分类信息
            if (StringUtils.equals(SensConst.OPTIONS.get(BlogPropertiesEnum.NEW_COMMENT_NEED_CHECK.getProp()), TrueFalseEnum.TRUE.getDesc()) || SensConst.OPTIONS.get(BlogPropertiesEnum.NEW_COMMENT_NEED_CHECK.getProp()) == null) {
                if (isEmailToAdmin || isEmailToParent) {
                    comment.setCommentStatus(CommentStatusEnum.PUBLISHED.getCode());
                } else {
                    comment.setCommentStatus(CommentStatusEnum.CHECKING.getCode());
                }
            } else {
                comment.setCommentStatus(CommentStatusEnum.PUBLISHED.getCode());
            }
            commentService.saveByComment(comment);
            //6.短信通知
            if (isEmailToParent) {
                new EmailToParent(comment, lastComment, post).start();
            }
            //评论自己的文章不要发短信
            if (isEmailToAdmin) {
                new EmailToAdmin(comment, post).start();
            }
            if (StringUtils.equals(SensConst.OPTIONS.get(BlogPropertiesEnum.NEW_COMMENT_NEED_CHECK.getProp()), TrueFalseEnum.TRUE.getDesc()) || SensConst.OPTIONS.get(BlogPropertiesEnum.NEW_COMMENT_NEED_CHECK.getProp()) == null) {
                return new JsonResult(ResultCodeEnum.SUCCESS.getCode(), "你的评论已经提交，待博主审核之后可显示。");
            } else {
                return new JsonResult(ResultCodeEnum.SUCCESS.getCode(), "你的评论已经提交，刷新后即可显示。");
            }
        } catch (Exception e) {
            return new JsonResult(ResultCodeEnum.FAIL.getCode(), "评论失败！");
        }
    }

    /**
     * 发送邮件给博主
     */
    class EmailToAdmin extends Thread {
        private Comment comment;
        private Post post;

        private EmailToAdmin(Comment comment, Post post) {
            this.comment = comment;
            this.post = post;
        }

        @Override
        public void run() {
            if (StringUtils.equals(SensConst.OPTIONS.get(BlogPropertiesEnum.SMTP_EMAIL_ENABLE.getProp()), TrueFalseEnum.TRUE.getDesc()) && StringUtils.equals(SensConst.OPTIONS.get(BlogPropertiesEnum.NEW_COMMENT_NOTICE.getProp()), TrueFalseEnum.TRUE.getDesc())) {
                try {
                    //发送邮件到博主
                    User user = userService.findByUserId(post.getUserId());
                    if (user != null && user.getUserEmail() != null) {
                        Map<String, Object> map = new HashMap<>(5);
                        map.put("author", user.getUserDisplayName());
                        map.put("pageName", post.getPostTitle());
                        if (StringUtils.equals(post.getPostType(), PostTypeEnum.POST_TYPE_POST.getDesc())) {
                            map.put("pageUrl", SensConst.OPTIONS.get(BlogPropertiesEnum.BLOG_URL.getProp()) + "/article/" + post.getPostId() + "#comment-id-" + comment.getCommentId());
                        } else if (StringUtils.equals(post.getPostType(), PostTypeEnum.POST_TYPE_NOTICE.getDesc())) {
                            map.put("pageUrl", SensConst.OPTIONS.get(BlogPropertiesEnum.BLOG_URL.getProp()) + "/notice/" + post.getPostId() + "#comment-id-" + comment.getCommentId());
                        } else {
                            map.put("pageUrl", SensConst.OPTIONS.get(BlogPropertiesEnum.BLOG_URL.getProp()) + "/p/" + post.getPostUrl() + "#comment-id-" + comment.getCommentId());
                        }
                        map.put("visitor", comment.getCommentAuthor());
                        map.put("commentContent", comment.getCommentContent());
                        mailService.sendTemplateMail(user.getUserEmail(), "有新的评论", map, "common/mail_template/mail_admin.ftl");
                    }
                } catch (Exception e) {
                    log.error("邮件服务器未配置：{}", e.getMessage());
                }
            }
        }
    }

    /**
     * 发送邮件给被评论方
     */
    class EmailToParent extends Thread {
        private Comment comment;
        private Comment lastComment;
        private Post post;

        private EmailToParent(Comment comment, Comment lastComment, Post post) {
            this.comment = comment;
            this.lastComment = lastComment;
            this.post = post;
        }

        @Override
        public void run() {
            //发送通知给对方
            if (StringUtils.equals(SensConst.OPTIONS.get(BlogPropertiesEnum.SMTP_EMAIL_ENABLE.getProp()), TrueFalseEnum.TRUE.getDesc()) && StringUtils.equals(SensConst.OPTIONS.get(BlogPropertiesEnum.NEW_COMMENT_NOTICE.getProp()), TrueFalseEnum.TRUE.getDesc())) {
                if (Validator.isEmail(lastComment.getCommentAuthorEmail())) {
                    Map<String, Object> map = new HashMap<>(8);
                    map.put("blogTitle", SensConst.OPTIONS.get(BlogPropertiesEnum.BLOG_TITLE.getProp()));
                    map.put("commentAuthor", lastComment.getCommentAuthor());
                    map.put("pageName", post.getPostTitle());
                    if (StringUtils.equals(post.getPostType(), PostTypeEnum.POST_TYPE_POST.getDesc())) {
                        map.put("pageUrl", SensConst.OPTIONS.get(BlogPropertiesEnum.BLOG_URL.getProp()) + "/article/" + post.getPostId() + "#comment-id-" + comment.getCommentId());
                    } else if (StringUtils.equals(post.getPostType(), PostTypeEnum.POST_TYPE_NOTICE.getDesc())) {
                        map.put("pageUrl", SensConst.OPTIONS.get(BlogPropertiesEnum.BLOG_URL.getProp()) + "/notice/" + post.getPostId() + "#comment-id-" + comment.getCommentId());
                    } else {
                        map.put("pageUrl", SensConst.OPTIONS.get(BlogPropertiesEnum.BLOG_URL.getProp()) + "/p/" + post.getPostUrl() + "#comment-id-" + comment.getCommentId());
                    }
                    map.put("commentContent", lastComment.getCommentContent());
                    map.put("replyAuthor", comment.getCommentAuthor());
                    map.put("replyContent", comment.getCommentContent());
                    map.put("blogUrl", SensConst.OPTIONS.get(BlogPropertiesEnum.BLOG_URL.getProp()));
                    mailService.sendTemplateMail(
                            lastComment.getCommentAuthorEmail(), "您在" + SensConst.OPTIONS.get(BlogPropertiesEnum.BLOG_TITLE.getProp()) + "的评论有了新回复", map, "common/mail_template/mail_reply.ftl");
                }
            }
        }
    }

}

