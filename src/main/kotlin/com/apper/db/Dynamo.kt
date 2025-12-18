import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import java.net.URI
import software.amazon.awssdk.regions.Region

object DynamoDbFactory {

    val dynamoDb: DynamoDbClient = DynamoDbClient.builder()
        .endpointOverride(URI.create("http://localhost:8000"))
        .region(Region.US_EAST_1) // required but ignored
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create("dummy", "dummy")
            )
        )
        .build()
}
