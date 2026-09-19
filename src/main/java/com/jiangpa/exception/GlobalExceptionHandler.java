package com.jiangpa.exception;

import com.jiangpa.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import javax.validation.ConstraintViolationException;


/**
 * 全局异常处理器。
 *
 * <p>职责只有一件事：<b>把各类异常翻译成统一的 {@link Result} 结构</b>，让前端永远只处理一套响应格式
 * （配合项目铁律「HTTP 状态码恒 200，业务状态放在 body 的 code 里」）。
 *
 * <p><b>为什么 Controller 和 Service 里到处 throw，却不用写 try/catch</b>：
 * {@code @RestControllerAdvice} 会拦截所有 Controller 抛出的异常，就近匹配 {@code @ExceptionHandler}。
 * Service 只管抛 {@link BusinessException}，错误码和提示语的翻译集中在这一个文件里。
 *
 * <p><b>两个容易搞混的点</b>：
 * <ol>
 *   <li><b>返回值不是 HTTP 状态码</b>。这里方法返回 {@code Result}，HTTP 层始终是 200，
 *       真正的状态在 {@code Result.code} 里（见 {@link Result}）。</li>
 *   <li><b>多个 handler 命中时不用管声明顺序</b>。Spring 会挑<b>类型最具体</b>的那个，
 *       所以 {@code MethodArgumentNotValidException}（子类）会优先于 {@link BindException}（父类），
 *       两个都存在时不会冲突。</li>
 * </ol>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 处理 {@code @RequestBody} 上的 {@code @Valid} 校验失败（POST / PUT 的 JSON 请求体）。
     *
     * <p>触发场景：登录、注册、发文章、改昵称、改角色等接口的 JSON 字段不满足约束
     * （如 {@code @NotBlank} 为空、{@code @Size} 越界）。
     *
     * <p>返回：取<b>第一条</b>字段错误的提示语，code = 400。
     *
     * <p>注意：这是 {@link BindException} 的<b>子类</b>，所以必须单独处理 ——
     * 否则会和 GET 的 query 参数校验混在一起，拿不到字段级提示语。
     */
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public Result<?> handleValidation(MethodArgumentNotValidException e) {
        log.warn(e.getMessage(), e);
        if (e.getBindingResult().getFieldErrors().isEmpty()) {
            return Result.error("服务器错误");
        }
        return Result.paramError(e.getBindingResult().getFieldErrors().get(0).getDefaultMessage());
    }

    /**
     * 处理方法参数上的约束校验失败（{@code @Validated} 标在类上时，
     * {@code @RequestParam} / {@code @PathVariable} 上的 {@code @Min} 等注解触发）。
     *
     * <p>和上面那个的区别：这个是<b>单个参数</b>的约束校验（不是请求体对象），
     * 异常里直接带完整信息，不需要再从 BindingResult 取字段错误。
     *
     * <p>返回：code = 400。
     */
    @ExceptionHandler(value = ConstraintViolationException.class)
    public Result<?> handleUnexpected(ConstraintViolationException e) {
        log.warn(e.getMessage(), e);
        return Result.paramError(e.getMessage());
    }

    /**
     * 处理数据库唯一索引冲突。
     *
     * <p>触发场景：并发注册同名用户（应用层"先查后插"之间有竞态窗口）、
     * 并发创建同名分类、<b>逻辑删除后重名复用</b>（已删除行的物理数据仍占着唯一索引）。
     *
     * <p>返回：code = 400，文案用<b>中性</b>的「数据不可复用！」——
     * 因为同一个处理器要服务 {@code tb_user} 和 {@code tb_category} 两张表，
     * 提示语不能偏向任何一方（早期硬编码"用户名已存在！"导致分类重名时回一句用户名的事）。
     *
     * <p>和 Service 层查重的关系：查重是<b>常态路径</b>（给友好提示），
     * 唯一索引是<b>并发兜底</b>（保证不写入重复数据）。两者缺一不可：
     * 只有查重则并发下会插入重复数据；只有索引则用户看到 500。
     */
    @ExceptionHandler(value = DuplicateKeyException.class)
    public Result<?> handleDuplicateKey(DuplicateKeyException e) {
        log.warn(e.getMessage(), e);
        return Result.paramError("数据不可复用！");
    }

    /**
     * 处理业务异常 —— <b>这个项目里最主要的一类</b>。
     *
     * <p>Service 层失败时统一 {@code throw new BusinessException(code, "提示语")}，
     * 不返回 Result。所以这条 handler 覆盖了绝大部分"预期内的失败"：
     * 404 资源不存在、403 无权操作、400 参数/状态不合法、401 凭证失效、
     * 以及降级场景的 503（Redis 不可用）和限流的 429。
     *
     * <p>返回：<b>原样透传 {@code e.getCode()}</b>，不做任何映射 ——
     * 状态码由抛出方决定，处理器只负责包装成统一结构。
     *
     * <p>日志用 {@code warn}：这些是预期内的业务结果，不是故障，不需要 {@code error} 级别惊动告警。
     */
    @ExceptionHandler(value = BusinessException.class)
    public Result<?> handleBusiness(BusinessException e) {
        log.warn(e.getMessage(), e);
        return Result.build(e.getCode(), e.getMessage(),  null);
    }

    /**
     * 处理请求体<b>根本解析不了</b>的情况。
     *
     * <p>触发场景：JSON 格式写错（少括号、多了逗号）、请求体不是合法 JSON、
     * 字段类型对不上（如把字符串塞进 Long 字段）。
     *
     * <p>返回：code = 400「请求体格式错误！」，而不是掉到兜底返 500。
     *
     * <p><b>和 {@code @Valid} 校验失败的区别（容易混）</b>：
     * <ul>
     *   <li>这条：报文<b>语法</b>层面就解析不了 —— 排查方向是 JSON 格式和 {@code Content-Type}</li>
     *   <li>{@link MethodArgumentNotValidException}：报文能解析成对象，但<b>字段值</b>不满足约束
     *       —— 排查方向是 DTO 上的注解</li>
     * </ul>
     * 两者都返回 400，但根因和排查方向完全不同。
     */
    @ExceptionHandler(value = HttpMessageNotReadableException.class)
    public Result<?> handleHttpMessageNotReadableException(HttpMessageNotReadableException e){
        log.warn(e.getMessage(), e);
        return Result.paramError("请求体格式错误！");
    }

    /**
     * 处理 {@code Content-Type} 不被支持的情况。
     *
     * <p>触发场景：客户端发 {@code text/plain}、{@code application/xml} 等，
     * 而接口只接受 {@code application/json}。
     *
     * <p>返回：code = 400（严格按 HTTP 语义应该是 415，但项目约定业务码放在 body 里，
     * 这里统一用 400 表示"客户端请求有问题"）。
     */
    @ExceptionHandler(value = HttpMediaTypeNotSupportedException.class)
    public Result<?> handleHttpMediaTypeNotSupportedException(HttpMediaTypeNotSupportedException e){
        log.warn(e.getMessage(), e);
        return Result.paramError("不支持的content-type!");
    }

    /**
     * 处理 {@code @ModelAttribute} + {@code @Valid} 校验失败（<b>GET 请求的 query 参数</b>）。
     *
     * <p>触发场景：分页参数越界，如 {@code GET /user/list?pageSize=999}、
     * {@code ?pageNum=0}。{@code PageQueryDTO} 上有 {@code @Min} / {@code @Max}。
     *
     * <p>返回：取第一条字段错误的提示语，code = 400。
     *
     * <p><b>⚠️ 为什么必须有这一条</b>：{@code @RequestBody} 校验失败抛的是
     * {@link MethodArgumentNotValidException}，而 {@code @ModelAttribute} 校验失败抛的是
     * <b>{@link BindException}</b> —— 两者是父子关系但是不同异常。
     * 少了这条，GET 接口参数越界会掉到兜底 handler 返 <b>500</b>，
     * 表现为"服务器开小差了"（服务器没问题，是参数不合法）。
     */
    @ExceptionHandler(value = BindException.class)
    public Result<?> handleBind(BindException e) {
        log.warn(e.getMessage(), e);
        if (e.getBindingResult().getFieldErrors().isEmpty()) {
            return Result.paramError("参数错误");
        }
        // 取第一个字段错误，和 handleValidation 保持一致的风格
        return Result.paramError(e.getBindingResult().getFieldErrors().get(0).getDefaultMessage());
    }

    /**
     * 处理 {@code @RequestParam} 标记必填但客户端没传的情况。
     *
     * <p>触发场景：{@code GET /comment/list} 没带 {@code articleId}。
     *
     * <p>返回：code = 400「缺少必填参数：xxx」，把参数名回给调用方，方便定位。
     *
     * <p><b>⚠️ 这条还有个"拉回项目约定"的作用</b>：{@link MissingServletRequestParameterException}
     * 属于 Spring MVC 的"标准异常"，默认会被 {@code DefaultHandlerExceptionResolver}
     * 解析成<b>真实 HTTP 400</b>（还会带上 Spring 的默认错误页格式）。
     * 而本项目约定「HTTP 状态码恒 200，错误放在 body 的 code 里」——
     * 不显式接管的话，前端会收到一个与项目格式不一致的响应，
     * 原本统一的解析逻辑反而会崩。这就是"全局异常处理器"存在的意义之一。
     */
    @ExceptionHandler(value = MissingServletRequestParameterException.class)
    public Result<?> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn(e.getMessage(), e);
        return Result.paramError("缺少必填参数：" + e.getParameterName());
    }

    /**
     * 处理 {@code @RequestParam} / {@code @PathVariable} 传了值但<b>类型转换失败</b>。
     *
     * <p>触发场景：{@code GET /comment/list?articleId=abc}（要 Long 给了字符串）、
     * {@code GET /user/xyz}（路径变量要 Long）。
     *
     * <p>返回：code = 400「参数类型错误：xxx」，同样把参数名带回。
     *
     * <p>和"缺少必填参数"的区别：那个是<b>没传</b>，这个是<b>传了但转不过去</b>。
     * 分开处理是为了让前端能给出准确提示（"请填写" vs "格式不对"）。
     */
    @ExceptionHandler(value = MethodArgumentTypeMismatchException.class)
    public Result<?> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn(e.getMessage(), e);
        return Result.paramError("参数类型错误：" + e.getName());
    }

    /**
     * 兜底处理器 —— <b>最后一道防线，只接"未预期"的异常</b>。
     *
     * <p>它捕获的是上面所有 handler 都没覆盖的 {@code Exception}（NPE、类型转换、
     * 第三方库异常……）。走到这里说明<b>代码有 Bug</b>，不是业务规则拒绝。
     *
     * <p>返回：code = 500，文案故意模糊（"服务器开小差了"）——
     * 不把堆栈或内部细节暴露给客户端，细节只进日志。
     *
     * <p><b>日志用 {@code error}</b>（上面几条都是 warn）：这是唯一需要惊动告警的级别。
     *
     * <p><b>排查经验</b>：如果某个接口"本该返 400 却返了 500"，八成是这类情况 ——
     * 抛出的异常类型不在上面的清单里。例如早期 {@code @ModelAttribute} 校验失败、
     * {@code @RequestParam} 缺失都曾掉到这里，表现为"服务器开小差了"，
     * 看起来像服务端故障，实际是客户端的请求问题。
     */
    @ExceptionHandler(value = Exception.class)
    public Result<?> handleException(Exception e) {
        log.error(e.getMessage(), e);
        return Result.error("服务器开小差了，请稍后再试");
    }
}
