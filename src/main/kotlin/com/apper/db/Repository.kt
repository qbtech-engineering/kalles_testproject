import com.apper.model.DataClasses
import com.apper.model.av
import com.apper.model.toIsoString
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.QueryRequest
import software.amazon.awssdk.services.dynamodb.model.ScanRequest

class ApplicationRepository(val dynamoDb: DynamoDbClient) {
    private val tableName = "Applications"

    fun putApplication(application: DataClasses.Application) {
        val item = mapOf(
            "name" to AttributeValue.builder().s(application.name).build()
        )
        dynamoDb.putItem { it.tableName(tableName).item(item) }
    }

    fun getAll(): List<DataClasses.Application> {
        val response = dynamoDb.scan { it.tableName(tableName) }
        return response.items().map {
            DataClasses.Application(
                name = it["name"]?.s() ?: ""
            )
        }
    }
}


class DeploymentRepository(val dynamoDb: DynamoDbClient) {

    fun putDeployment(deployment: DataClasses.Deployment) {
        val insertTs = System.currentTimeMillis()

        val item = mapOf(
            "applicationName" to deployment.applicationName.av(),
            "env_timestamp" to "${deployment.env}#${insertTs}".av(),
            "timestamp" to insertTs.av(),
            "env" to deployment.env.av(),
            "version" to deployment.version.av(),
            "deployer" to deployment.deployer.av()
        )

        val request = PutItemRequest.builder()
            .tableName("Deployments")
            .item(item)
            .build()

        dynamoDb.putItem(request)
        println("Inserted deployment for ${deployment.applicationName} in ${deployment.env}")
    }

    fun scanAllDeployments(dynamoDb: DynamoDbClient): List<DataClasses.DeploymentResponse> {
        val response = dynamoDb.scan(
            ScanRequest.builder()
                .tableName("Deployments")
                .build()
        )
        return response.items().map { it.toDeploymentResponse() }
    }

    fun getLatestDeploymentForAppEnv(
        dynamoDb: DynamoDbClient,
        appName: String,
        env: String,
    ): DataClasses.DeploymentResponse? {
        val response = dynamoDb.query(
            QueryRequest.builder()
                .tableName("Deployments")
                .keyConditionExpression("applicationName = :app AND begins_with(env_timestamp, :env)")
                .expressionAttributeValues(
                    mapOf(
                        ":app" to AttributeValue.builder().s(appName).build(),
                        ":env" to AttributeValue.builder().s("$env#").build()
                    )
                )
                .scanIndexForward(false) // descending → latest first
                .limit(1) // only need latest
                .build()
        )

        return response.items().firstOrNull()?.toDeploymentResponse()
    }

    fun getLatestDeploymentsForEnv(
        dynamoDb: DynamoDbClient,
        env: String,
    ): List<DataClasses.DeploymentResponse> {
        val response = dynamoDb.query(
            QueryRequest.builder()
                .tableName("Deployments")
                .indexName("EnvIndex")
                .keyConditionExpression("env = :env")
                .expressionAttributeValues(
                    mapOf(":env" to AttributeValue.builder().s(env).build())
                )
                .scanIndexForward(false)
                .build()
        )

        val latestPerApp = mutableMapOf<String, DataClasses.DeploymentResponse>()
        response.items().forEach { item ->
            val d = item.toDeploymentResponse()
            latestPerApp.compute(d.application) { _, existing ->
                if (existing == null || d.timestamp > existing.timestamp) d else existing
            }
        }
        return latestPerApp.values.toList()
    }

    fun getAllByAppAndEnv(dynamoDb : DynamoDbClient, application: String, env: String) : List<DataClasses.DeploymentResponse> {
        // Case: both application and env → all deployments for app in env
        val response = dynamoDb.query(
            QueryRequest.builder()
                .tableName("Deployments")
                .keyConditionExpression("applicationName = :app AND begins_with(env_timestamp, :env)")
                .expressionAttributeValues(
                    mapOf(
                        ":app" to AttributeValue.builder().s(application).build(),
                        ":env" to AttributeValue.builder().s("$env#").build()
                    )
                )
                .scanIndexForward(false) // latest first
                .build()
        )
        return response.items().map { it.toDeploymentResponse() }
    }

    fun getByApplication(appName: String): List<DataClasses.DeploymentResponse> {
        val response = dynamoDb.query(
            QueryRequest.builder()
                .tableName("Deployments")
                .keyConditionExpression("applicationName = :app")
                .expressionAttributeValues(
                    mapOf(":app" to AttributeValue.builder().s(appName).build())
                )
                .scanIndexForward(false)
                .build()
        )

        val latestPerEnv = mutableMapOf<String, DataClasses.DeploymentResponse>()
        response.items().forEach { item ->
            val d = item.toDeploymentResponse()
            latestPerEnv.compute(d.env) { _, existing ->
                if (existing == null || d.timestamp > existing.timestamp) d else existing
            }
        }
        return latestPerEnv.values.toList()
    }

    fun getLatestOfEnv(appNames: List<String>, env: String): List<DataClasses.DeploymentResponse> {
        return appNames.mapNotNull {
            getLatestDeploymentForAppEnv(dynamoDb, it, env)
        }
    }
}

fun Map<String, AttributeValue>.toDeploymentResponse(): DataClasses.DeploymentResponse =
    DataClasses.DeploymentResponse(
        application = this["applicationName"]!!.s(),
        timestamp = this["timestamp"]!!.n().toLong(),
        dateTime = this["timestamp"]!!.n().toLong().toIsoString(),
        version = this["version"]!!.s(),
        deployer = this["deployer"]!!.s(),
        env = this["env"]!!.s()
    )

