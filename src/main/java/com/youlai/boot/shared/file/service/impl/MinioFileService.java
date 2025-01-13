package com.youlai.boot.shared.file.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.youlai.boot.shared.file.service.FileService;
import com.youlai.boot.shared.file.model.FileInfo;
import io.minio.*;
import io.minio.errors.*;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

/**
 * MinIO 文件上传服务类
 *
 * @author haoxr
 * @since 2023/6/2
 */
@Component
@ConditionalOnProperty(value = "oss.type", havingValue = "minio")
@ConfigurationProperties(prefix = "oss.minio")
@RequiredArgsConstructor
@Data
public class MinioFileService implements FileService {

    /**
     * 服务Endpoint
     */
    private String endpoint;
    /**
     * 访问凭据
     */
    private String accessKey;
    /**
     * 凭据密钥
     */
    private String secretKey;
    /**
     * 存储桶名称
     */
    private String bucketName;
    /**
     * 自定义域名
     */
    private String customDomain;

    private MinioClient minioClient;

    // 依赖注入完成之后执行初始化
    @PostConstruct
    public void init() {
        minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        // 创建存储桶(存储桶不存在)
        // createBucketIfAbsent(bucketName);
    }


    /**
     * 上传文件
     *
     * @param file 表单文件对象
     * @return
     */
    @Override
    public FileInfo uploadFile(MultipartFile file) {

        // 创建存储桶(存储桶不存在)，如果有搭建好的minio服务，建议放在init方法中
        createBucketIfAbsent(bucketName);

        // 生成文件名(日期文件夹)
        String suffix = FileUtil.getSuffix(file.getOriginalFilename());
        String uuid = IdUtil.simpleUUID();
        String fileName = DateUtil.format(LocalDateTime.now(), "yyyyMMdd") + "/" + uuid + "." + suffix;
        //  try-with-resource 语法糖自动释放流
        try (InputStream inputStream = file.getInputStream()) {
            // 文件上传
            PutObjectArgs putObjectArgs = PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(fileName)
                    .contentType(file.getContentType())
                    .stream(inputStream, inputStream.available(), -1)
                    .build();
            minioClient.putObject(putObjectArgs);

            // 返回文件路径
            String fileUrl;
            // 未配置自定义域名
            if (StrUtil.isBlank(customDomain)) {
                GetPresignedObjectUrlArgs getPresignedObjectUrlArgs = GetPresignedObjectUrlArgs.builder()
                        .bucket(bucketName).object(fileName)
                        .method(Method.GET)
                        .build();

                fileUrl = minioClient.getPresignedObjectUrl(getPresignedObjectUrlArgs);
                fileUrl = fileUrl.substring(0, fileUrl.indexOf("?"));
            } else { // 配置自定义文件路径域名
                fileUrl = customDomain + '/' + bucketName + "/" + fileName;
            }

            FileInfo fileInfo = new FileInfo();
            fileInfo.setName(fileName);
            fileInfo.setUrl(fileUrl);
            return fileInfo;
        } catch (Exception e) {
            throw new RuntimeException("文件上传失败");
        }
    }


    /**
     * 删除文件
     *
     * @param filePath 文件路径  http://localhost:9000/default/20221120/test.jpg
     *
     * @return
     */
    @Override
    public boolean deleteFile(String filePath) {
        Assert.notBlank(filePath, "删除文件路径不能为空");
        try {
            String fileName;
            if (StrUtil.isNotBlank(customDomain)) {
                // https://oss.youlai.tech/default/20221120/test.jpg → 20221120/websocket.jpg
                fileName = filePath.substring(customDomain.length() + 1 + bucketName.length() + 1); // 两个/占了2个字符长度
            } else {
                // http://localhost:9000/default/20221120/test.jpg → 20221120/websocket.jpg
                fileName = filePath.substring(endpoint.length() + 1 + bucketName.length() + 1);
            }
            RemoveObjectArgs removeObjectArgs = RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(fileName)
                    .build();

            minioClient.removeObject(removeObjectArgs);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("文件删除失败", e);
        }
    }


    /**
     * PUBLIC桶策略
     * 如果不配置，则新建的存储桶默认是PRIVATE，则存储桶文件会拒绝访问 Access Denied
     *
     * @param bucketName
     * @return
     */
    private static String publicBucketPolicy(String bucketName) {
        /**
         * AWS的S3存储桶策略
         * Principal: 生效用户对象
         * Resource:  指定存储桶
         * Action: 操作行为
         */

        return "{\"Version\":\"2012-10-17\","
                + "\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":[\"*\"]},"
                + "\"Action\":[\"s3:ListBucketMultipartUploads\",\"s3:GetBucketLocation\",\"s3:ListBucket\"],"
                + "\"Resource\":[\"arn:aws:s3:::" + bucketName + "\"]},"
                + "{\"Effect\":\"Allow\"," + "\"Principal\":{\"AWS\":[\"*\"]},"
                + "\"Action\":[\"s3:ListMultipartUploadParts\",\"s3:PutObject\",\"s3:AbortMultipartUpload\",\"s3:DeleteObject\",\"s3:GetObject\"],"
                + "\"Resource\":[\"arn:aws:s3:::" + bucketName + "/*\"]}]}";
    }

    /**
     * 创建存储桶(存储桶不存在)
     *
     * @param bucketName
     */
    @SneakyThrows
    private void createBucketIfAbsent(String bucketName) {
        BucketExistsArgs bucketExistsArgs = BucketExistsArgs.builder().bucket(bucketName).build();
        if (!minioClient.bucketExists(bucketExistsArgs)) {
            MakeBucketArgs makeBucketArgs = MakeBucketArgs.builder().bucket(bucketName).build();

            minioClient.makeBucket(makeBucketArgs);

            // 设置存储桶访问权限为PUBLIC， 如果不配置，则新建的存储桶默认是PRIVATE，则存储桶文件会拒绝访问 Access Denied
            SetBucketPolicyArgs setBucketPolicyArgs = SetBucketPolicyArgs
                    .builder()
                    .bucket(bucketName)
                    .config(publicBucketPolicy(bucketName))
                    .build();
            minioClient.setBucketPolicy(setBucketPolicyArgs);
        }
    }
}
