/*
 * Copyright 2019 Lightbend Inc.
 */

package com.akkaserverless.samples.eventing.shoppingcart;

import com.akkaserverless.javasdk.action.Action;
import com.akkaserverless.javasdk.action.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import shopping.cart.model.ShoppingCart;

@Action
public class TopicPublisherAction {
  private static final Logger LOG = LoggerFactory.getLogger(TopicPublisherAction.class);

  @Handler
  public ShoppingCart.ItemAdded publishAdded(ShoppingCart.ItemAdded in) {
    LOG.info("Publishing: '{}' to topic", in);
    return in;
  }

  @Handler
  public ShoppingCart.ItemRemoved publishRemoved(ShoppingCart.ItemRemoved in) {
    LOG.info("Publishing: '{}' to topic", in);
    return in;
  }
}
