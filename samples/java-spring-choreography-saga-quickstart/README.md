# Choreography Saga Quickstart

This quickstart sample demonstrates how to implement a Choreography Saga in Kalix.

This project explores the usage of Event Sourced Entities, Value Entities and Actions.  
To understand more about these components, see [Developing services](https://docs.kalix.io/services/) and check 
Java-SDK [official documentation](https://docs.kalix.io/java/index.html). 

## What is a Choreography Saga?

A **Choreography Saga** is a distributed transaction pattern that helps you manage transactions across multiple services.

In the context of event-driven applications, a **Choreography Saga** is implemented as a sequence of transactions,
each of which publishes an event or state change notification that triggers the next operation in the saga.
If an operation fails because it violates a business rule, then the saga can execute a series of compensating transactions
that undo the changes made by the previous operations.

In Kalix, in addition to events from Event Sourced Entities, you can also subscribe to state changes from Value Entities.
To subscribe to events or state changes, we can use Kalix Actions with the appropriate subscription annotations.

You can create a Choreography Saga to manage transactions across multiple entities in a single service, or across multiple services.
This example implements a choreography that manages transactions across two entities in the same service.

## Cross Entity Field Uniqueness

A common challenge in event-sourced applications is called the _Set-Based Consistency Validation_ problem. It arises when we need to ensure that a particular field is unique across all entities in the system. For example, a user may have a unique identifier (e.g. social security number) that can be used as a unique entity ID, but may also have an email address that needs to be unique across all users in the system.

In an event-sourced application, the events emitted by an entity are stored in a journal that is optimized for storing events payload without prior knowledge of the structure of the data. As such, it is not possible to add a unique constraint.

A **Choreography Saga** can be implemented as a solution to this challenge. In addition to the `UserEntity`, we can implement a second entity to act as a barrier. This entity, called the `UniqueEmailEntity`, will be responsible for ensuring that each email address is associated with only one user.  The unique ID of the `UniqueEmailEntity` will be the email address itself. Thus, we can guarantee that there is only one instance of this entity per email.

When a request to create a new `UserEntity` is received, we first attempt to reserve the email address using the 
`UniqueEmailEntity`. If it's not already in use, we proceed to create the `UserEntity`. Once the `UserEntity` has been created, the `UniqueEmailEntity` is marked as CONFIRMED. If the email address is already in use, the request to create the `UserEntity` will fail.

### Successful Scenario

The sunny day scenario is illustrated in the following diagram:

![Successful Flow](flow-successful.png?raw=true)

All incoming requests are handled by the `ApplicationController` which is implemented using a Kalix Action. 

1. upon receiving a request to create a new User, the `ApplicationController` will first reserve the email.
2. it will then create the User. 
   1. the `UserEventsSubscriber` Action listens to the User's event 
   2. and mark the UniqueEmail entity as soon as it 'sees' that a User has been created.
      
### Failure Scenario

As these are two independent transactions, we also need to consider the failure scenarios. For example, a request to 
reserve the email address may succeed, but the request to create the user may fail. In such a scenario, we end up with 
an email address that is reserved but not associated with a user.

The failure scenario is illustrated in the following diagram:

![Failure Flow](flow-failure.png?raw=true)

1. upon receiving a request to create a new User, the `ApplicationController` will first reserve the email.
   1. in the background, the `UniqueEmailSubscriber` Action listens to state changes from `UniqueEmailEntity`
   2. when it detects that an email was reserved it schedules a timer to un-reserve it after a certain amount of time. 
   3. when the timer fires, the email is un-reserved if the `UniqueEmailEntity` is still in RESERVED state.
2. then it tries to create the User, but it fails. As such, the email will never be confirmed, but the timer 
   will unlock it.

> [!NOTE]
> Everything on the side of the `UniqueEmailSubscriber` is happening in the background and independent of the success or failure of the User creation.

### Full Successful Scenario

Now that we have covered the failure scenario, we can have a look at the full picture for the successful scenario:

![Full Successful Flow](flow-full.png?raw=true)

We need to understand that `UniqueEmailSubscriber` and `UserEventsSubscriber` are two autonomous components that
once deployed, run in the background and do their job whenever they receive an event or state change notification.

In the case where a user is successfully created, the `UniqueEmailSubscriber` will still react to the `UniqueEmailEntity` reservation and therefore still schedule a timer to un-reserve it. However, since the user has been created, the `UserEventsSubscriber` will change the `UniqueEmailEntity` state to CONFIRMED and this will trigger another state change notification to the `UniqueEmailSubscriber` which will cancel the timer.

Although simple, this example shows how to implement a **Choreography Saga** in Kalix. We have two entities that 
influence each other, and we have two actions that listen to events and state changes and guarantee that the whole converges to a consistent state.

## Running and exercising this sample

To start your service locally, run:

```shell
mvn kalix:runAll -Demail.confirmation.timeout=10s
```

This command will start your Kalix service and a companion Kalix Runtime as configured in [docker-compose.yml](./docker-compose.yml) file.

The `email.confirmation.timeout` setting is used to configure the timer to fire after 10 seconds. In other words, if 
the email is not confirmed within this time, it will be released. The default value for this setting is 2 hours (see the `src/resources/application.conf` file). For demo purposes, it's convenient to set it to a few seconds so we don't have to wait.

### create user identified by 001

```shell
curl localhost:9000/api/users/001 \
  --header "Content-Type: application/json" \
  -XPOST \
  --data '{ "name":"John Doe","country":"Belgium", "email":"doe@acme.com" }'
```

Check the logs of `UniqueEmailSubscriber` and `UserEventsSubscriber` to see how the saga is progressing.

### check status for email doe@acme.com

```shell
curl localhost:9000/api/emails/doe@acme.com
```
The status of the email will be RESERVED or CONFIRMED, depending on whether the saga has been completed or not. 

### create user identified by 002

```shell
curl localhost:9000/api/users/002 \
  --header "Content-Type: application/json" \
  -XPOST \
  --data '{ "name":"Anne Doe","country":"Belgium", "email":"doe@acme.com" }'
```
A second user with the same email address will fail.

### try to create an invalid user

```shell
curl localhost:9000/api/users/003 \
  --header "Content-Type: application/json" \
  -XPOST \
  --data '{ "country":"Belgium", "email":"invalid@acme.com" }'
```

Note that the 'name' is not stored. This will result in the email address `invalid@acme.com` being reserved but not confirmed.

Check the logs of `UniqueEmailSubscriber` to see how the saga is progressing.

### check status for email invalid@acme.com

```shell
curl localhost:9000/api/emails/invalid@acme.com
```

The status of the email will be RESERVED or NOT_USED, depending on whether the timer to un-reserve it has fired or not.

### Bonus: change an email address

Change the email address of user 001 to `john.doe@acme.com`. Inspect the code to understand how it re-uses the existing saga.

```shell
curl localhost:9000/api/users/001/change-email \
  --header "Content-Type: application/json" \
  -XPUT \
  --data '{ "newEmail": "john.doe@acme.com" }'
```

Check the logs of `UniqueEmailSubscriber` and `UserEventsSubscriber` to see how the saga is progressing.

### check status for email doe@acme.com

```shell
curl localhost:9000/api/emails/doe@acme.com
```

The status of the email will be CONFIRMED or NOT_USED, depending on whether the saga has been completed or not.

## Deploying

To deploy your service, install the `kalix` CLI as documented in
[Setting up a local development environment](https://docs.kalix.io/setting-up/)
and configure a Docker Registry to upload your docker image to.

You will need to update the `dockerImage` property in the `pom.xml` and refer to
[Configuring registries](https://docs.kalix.io/projects/container-registries.html)
for more information on how to make your docker image available to Kalix.

Finally, you can use the [Kalix Console](https://console.kalix.io)
to create a project and then deploy your service into the project either by using `mvn deploy kalix:deploy` which
will conveniently package, publish your docker image, and deploy your service to Kalix, or by first packaging and
publishing the docker image through `mvn deploy` and then deploying the image
through the `kalix` CLI.


