package com.teacup.teacuppicturebackend.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.region.Region;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
@ConfigurationProperties(prefix = "cos.client")
@Data
public class CosClientConfig {

    private String host;


    private String secretId;


    private String secretKey;


    private String region;


    private String bucket;

    @Bean
    public COSClient cosClient() {
        if (secretId == null || secretId.isEmpty()) {
            return new COSClient(new BasicCOSCredentials("dummy", "dummy"), new ClientConfig(new Region(region == null ? "ap-guangzhou" : region)));
        }

        COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);

        ClientConfig clientConfig = new ClientConfig(new Region(region));

        return new COSClient(cred, clientConfig);
    }
}