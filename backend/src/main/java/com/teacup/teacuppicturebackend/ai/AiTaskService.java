package com.teacup.teacuppicturebackend.ai;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.teacup.teacuppicturebackend.api.v1.V1Exception;
import com.teacup.teacuppicturebackend.api.v1.model.M1Dtos;
import com.teacup.teacuppicturebackend.api.v1.model.M2Dtos;
import com.teacup.teacuppicturebackend.mapper.AiModelMapper;
import com.teacup.teacuppicturebackend.mapper.AiQuotaUsageMapper;
import com.teacup.teacuppicturebackend.mapper.AiTaskMapper;
import com.teacup.teacuppicturebackend.mapper.PictureMapper;
import com.teacup.teacuppicturebackend.model.entity.AiModel;
import com.teacup.teacuppicturebackend.model.entity.AiQuotaUsage;
import com.teacup.teacuppicturebackend.model.entity.AiTask;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.service.UserService;
import com.teacup.teacuppicturebackend.storage.PictureStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class AiTaskService {
    private static final Set<String> TYPES = Set.of("generate", "outpaint");
    private static final Set<String> STATUSES = Set.of("queued", "running", "succeeded", "failed", "cancelled");
    private final AiModelMapper modelMapper;
    private final AiQuotaUsageMapper quotaMapper;
    private final AiTaskMapper taskMapper;
    private final PictureMapper pictureMapper;
    private final UserService userService;
    private final PictureStorage storage;
    private final int generateDailyLimit;
    private final int outpaintDailyLimit;
    private final ZoneId quotaZone;
    private AiTaskRunner runner;

    public AiTaskService(AiModelMapper modelMapper, AiQuotaUsageMapper quotaMapper, AiTaskMapper taskMapper,
                         PictureMapper pictureMapper, UserService userService, PictureStorage storage,
                         @Value("${teacup.ai.quota.generate-daily:100}") int generateDailyLimit,
                         @Value("${teacup.ai.quota.outpaint-daily:100}") int outpaintDailyLimit,
                         @Value("${teacup.ai.quota.zone:Asia/Shanghai}") String quotaZone) {
        this.modelMapper = modelMapper;
        this.quotaMapper = quotaMapper;
        this.taskMapper = taskMapper;
        this.pictureMapper = pictureMapper;
        this.userService = userService;
        this.storage = storage;
        this.generateDailyLimit = generateDailyLimit;
        this.outpaintDailyLimit = outpaintDailyLimit;
        this.quotaZone = ZoneId.of(quotaZone);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setRunner(AiTaskRunner runner) {
        this.runner = runner;
    }

    public List<M2Dtos.AiModelView> models() {
        return modelMapper.selectList(new LambdaQueryWrapper<AiModel>().eq(AiModel::getEnabled, 1).orderByAsc(AiModel::getId))
                .stream().map(this::modelView).toList();
    }

    public M2Dtos.AiQuotaSummary quotas(User user) {
        LocalDate date = LocalDate.now(quotaZone);
        return new M2Dtos.AiQuotaSummary(date, List.of(quotaView(user.getId(), date, "generate"), quotaView(user.getId(), date, "outpaint")));
    }

    @Transactional(rollbackFor = Exception.class)
    public M2Dtos.AiTaskView create(User user, M2Dtos.CreateAiTaskRequest input) {
        if (input == null || !TYPES.contains(input.type())) throw V1Exception.badRequest("AI 任务类型无效");
        String prompt = input.prompt() == null ? "" : input.prompt().trim();
        if (prompt.isBlank() || prompt.length() > 2000) throw V1Exception.badRequest("提示词长度必须为 1 到 2000 字");
        AiModel model = modelMapper.selectOne(new LambdaQueryWrapper<AiModel>().eq(AiModel::getCode, input.modelCode()).eq(AiModel::getEnabled, 1).last("LIMIT 1"));
        if (model == null || !strings(model.getCapabilities()).contains(input.type())) throw V1Exception.badRequest("模型不支持该任务类型");
        if (!strings(model.getSupportedRatios()).contains(input.ratio())) throw V1Exception.badRequest("图片比例不受支持");
        if (!strings(model.getSupportedQualities()).contains(input.quality())) throw V1Exception.badRequest("清晰度不受支持");
        Picture source = picture(user, input.sourcePictureId(), "outpaint".equals(input.type()));
        Picture reference = picture(user, input.referencePictureId(), false);
        if (reference != null && !Integer.valueOf(1).equals(model.getSupportsReference())) throw V1Exception.badRequest("模型不支持参考图");
        int cost = Math.max(1, model.getQuotaCost());
        consumeQuota(user.getId(), input.type(), cost);
        AiTask task = new AiTask();
        task.setUserId(user.getId()); task.setTaskType(input.type()); task.setModelId(model.getId()); task.setModelCode(model.getCode());
        task.setProvider(model.getProvider()); task.setProviderModel(model.getProviderModel()); task.setPrompt(prompt);
        task.setRatio(input.ratio()); task.setQuality(input.quality()); task.setSourcePictureId(source == null ? null : source.getId());
        task.setReferencePictureId(reference == null ? null : reference.getId()); task.setStatus("queued"); task.setQuotaCost(cost);
        task.setQuotaRefunded(0); task.setInvocationStarted(0); taskMapper.insert(task);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { runner.runAsync(task.getId()); }
        });
        return taskView(task, model);
    }

    public M2Dtos.AiTaskPage list(User user, int page, int pageSize, String status) {
        validatePage(page, pageSize);
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) throw V1Exception.badRequest("任务状态无效");
        LambdaQueryWrapper<AiTask> query = new LambdaQueryWrapper<AiTask>().eq(AiTask::getUserId, user.getId())
                .orderByDesc(AiTask::getCreateTime).orderByDesc(AiTask::getId);
        if (status != null && !status.isBlank()) query.eq(AiTask::getStatus, status);
        Page<AiTask> result = taskMapper.selectPage(new Page<>(page, pageSize), query);
        return new M2Dtos.AiTaskPage(result.getRecords().stream().map(this::taskView).toList(),
                new M1Dtos.PageMeta(page, pageSize, result.getTotal(), result.getPages()));
    }

    public M2Dtos.AiTaskView get(User user, long taskId) {
        return taskView(requireOwned(user, taskId));
    }

    @Transactional(rollbackFor = Exception.class)
    public M2Dtos.AiTaskView cancel(User user, long taskId) {
        AiTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiTask>().eq(AiTask::getId, taskId).last("FOR UPDATE"));
        if (task == null || !Objects.equals(task.getUserId(), user.getId())) throw V1Exception.notFound();
        if (!Set.of("queued", "running").contains(task.getStatus())) throw V1Exception.conflict("任务当前不可取消");
        boolean refund = "queued".equals(task.getStatus()) && !Integer.valueOf(1).equals(task.getInvocationStarted());
        task.setStatus("cancelled"); task.setFinishTime(LocalDateTime.now());
        if (refund) { refundQuota(task); task.setQuotaRefunded(1); }
        taskMapper.updateById(task);
        return taskView(task);
    }

    @Transactional(rollbackFor = Exception.class)
    public void fail(long taskId, String code, String reason) {
        AiTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiTask>().eq(AiTask::getId, taskId).last("FOR UPDATE"));
        if (task == null || "cancelled".equals(task.getStatus()) || "succeeded".equals(task.getStatus())) return;
        task.setStatus("failed"); task.setFailureCode(trim(code, 64)); task.setFailureReason(trim(reason, 500)); task.setFinishTime(LocalDateTime.now());
        if (!Integer.valueOf(1).equals(task.getQuotaRefunded())) { refundQuota(task); task.setQuotaRefunded(1); }
        taskMapper.updateById(task);
    }

    public Download download(User user, long taskId) {
        AiTask task = requireOwned(user, taskId);
        if (!"succeeded".equals(task.getStatus()) || task.getResultPictureId() == null) throw V1Exception.conflict("任务尚无可下载结果");
        Picture picture = pictureMapper.selectById(task.getResultPictureId());
        if (picture == null) throw V1Exception.notFound();
        if (picture.getObjectKey() == null || picture.getObjectKey().isBlank()) throw V1Exception.notFound();
        PictureStorage.StoredObject object = storage.load(picture.getObjectKey());
        return new Download(object.resource(), object.fileName(), org.springframework.http.MediaType.parseMediaType(object.contentType()));
    }

    @Transactional(rollbackFor = Exception.class)
    public M2Dtos.AiModelView updateModel(User admin, long modelId, M2Dtos.UpdateAiModelRequest input) {
        if (!userService.isAdmin(admin)) throw V1Exception.forbidden();
        AiModel model = modelMapper.selectById(modelId);
        if (model == null) throw V1Exception.notFound();
        if (input == null) throw V1Exception.badRequest("模型配置不能为空");
        if (input.name() != null && !input.name().isBlank()) model.setDisplayName(trim(input.name(), 128));
        if (input.capabilities() != null && !input.capabilities().isEmpty()) model.setCapabilities(JSONUtil.toJsonStr(input.capabilities()));
        if (input.ratios() != null && !input.ratios().isEmpty()) model.setSupportedRatios(JSONUtil.toJsonStr(input.ratios()));
        if (input.qualities() != null && !input.qualities().isEmpty()) model.setSupportedQualities(JSONUtil.toJsonStr(input.qualities()));
        if (input.supportsReference() != null) model.setSupportsReference(input.supportsReference() ? 1 : 0);
        if (input.quotaCost() != null) { if (input.quotaCost() < 1 || input.quotaCost() > 100) throw V1Exception.badRequest("配额成本无效"); model.setQuotaCost(input.quotaCost()); }
        if (input.enabled() != null) model.setEnabled(input.enabled() ? 1 : 0);
        modelMapper.updateById(model);
        return modelView(model);
    }

    private void consumeQuota(long userId, String type, int cost) {
        LocalDate date = LocalDate.now(quotaZone); quotaMapper.ensureRow(userId, date, type);
        AiQuotaUsage usage = quotaMapper.selectForUpdate(userId, date, type);
        if (usage.getUsedCount() + cost > limit(type)) throw V1Exception.conflict("今日 AI 配额已用完");
        usage.setUsedCount(usage.getUsedCount() + cost); quotaMapper.updateById(usage);
    }

    private void refundQuota(AiTask task) {
        LocalDate date = task.getCreateTime() == null ? LocalDate.now(quotaZone) : task.getCreateTime().atZone(quotaZone).toLocalDate();
        quotaMapper.ensureRow(task.getUserId(), date, task.getTaskType());
        AiQuotaUsage usage = quotaMapper.selectForUpdate(task.getUserId(), date, task.getTaskType());
        usage.setUsedCount(Math.max(0, usage.getUsedCount() - task.getQuotaCost())); quotaMapper.updateById(usage);
    }

    private M2Dtos.AiQuotaView quotaView(long userId, LocalDate date, String type) {
        AiQuotaUsage usage = quotaMapper.selectOne(new LambdaQueryWrapper<AiQuotaUsage>().eq(AiQuotaUsage::getUserId, userId)
                .eq(AiQuotaUsage::getUsageDate, date).eq(AiQuotaUsage::getTaskType, type));
        int used = usage == null ? 0 : usage.getUsedCount(); int limit = limit(type);
        return new M2Dtos.AiQuotaView(type, limit, used, Math.max(0, limit - used));
    }

    private int limit(String type) { return "generate".equals(type) ? generateDailyLimit : outpaintDailyLimit; }
    private Picture picture(User user, String id, boolean required) {
        if (id == null || id.isBlank()) { if (required) throw V1Exception.badRequest("扩图必须选择原图"); return null; }
        long parsed; try { parsed = Long.parseLong(id); } catch (NumberFormatException exception) { throw V1Exception.badRequest("图片 ID 无效"); }
        Picture picture = pictureMapper.selectById(parsed);
        if (picture == null || !Objects.equals(picture.getUserId(), user.getId()) || Integer.valueOf(1).equals(picture.getIsDelete())) throw V1Exception.notFound();
        return picture;
    }
    private AiTask requireOwned(User user, long taskId) { AiTask task = taskMapper.selectById(taskId); if (task == null || !Objects.equals(task.getUserId(), user.getId())) throw V1Exception.notFound(); return task; }
    private M2Dtos.AiTaskView taskView(AiTask task) { return taskView(task, modelMapper.selectById(task.getModelId())); }
    private M2Dtos.AiTaskView taskView(AiTask task, AiModel model) {
        return new M2Dtos.AiTaskView(task.getId().toString(), task.getTaskType(), modelView(model), task.getPrompt(), task.getRatio(), task.getQuality(), task.getStatus(),
                pictureRef(task.getSourcePictureId(), false), pictureRef(task.getReferencePictureId(), false), pictureRef(task.getResultPictureId(), true),
                task.getFailureCode(), task.getFailureReason(), Integer.valueOf(1).equals(task.getQuotaRefunded()), instant(task.getCreateTime()), instant(task.getStartTime()), instant(task.getFinishTime()),
                "succeeded".equals(task.getStatus()) ? "/ai/tasks/" + task.getId() + "/download" : null);
    }
    private M2Dtos.AiModelView modelView(AiModel model) { return new M2Dtos.AiModelView(model.getId().toString(), model.getCode(), model.getDisplayName(), strings(model.getCapabilities()), strings(model.getSupportedRatios()), strings(model.getSupportedQualities()), Integer.valueOf(1).equals(model.getSupportsReference()), model.getQuotaCost(), Integer.valueOf(1).equals(model.getEnabled())); }
    private M2Dtos.AiPictureRef pictureRef(Long id, boolean includeUrl) { if (id == null) return null; Picture p = pictureMapper.selectById(id); return p == null ? null : new M2Dtos.AiPictureRef(p.getId().toString(), p.getName(), p.getThumbnailUrl(), includeUrl ? p.getUrl() : null); }
    private List<String> strings(String json) { return json == null ? List.of() : JSONUtil.toList(json, String.class); }
    private static Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
    private static String trim(String value, int max) { if (value == null) return null; value = value.trim(); return value.length() > max ? value.substring(0, max) : value; }
    private static void validatePage(int page, int pageSize) { if (page < 1 || pageSize < 1 || pageSize > 100) throw V1Exception.badRequest("分页参数无效"); }

    public record Download(Resource resource, String fileName, org.springframework.http.MediaType mediaType) {}
}
