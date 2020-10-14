# Getting Started
[https://codelabs.developers.google.com/codelabs/build-your-first-android-app-kotlin/](tutorial)

# User Interface

# Background tasks

## Send `Advertise` message
 - if goTenna `Message_Queue` empty for > 1 minute, queue broadcast `Advertise` message: `advertise(pubkey@GID, hops=1)`
 
## Receive `Advertise` message
 - node not on the `Known_Nodes` list, then
   - add to `Known_Nodes` list
 - else, update `Last_Seen` field for node with current time
 
## Connect to `Known_Nodes`
 - for each `node` on `Known_Nodes` list
   - if `node.Last_Seen` time is < 5 minutes, then
     - if `node.channel` exists, then
       - if `node.connected` is False, then
         - if `node.Last_Tried` time > 1 minute, then
           - queue private `Connect` message to `node.GID`: `connect(pubkey@GID, hops=1)`
           - set `node.Last_Tried` field time to "Now"
 
## Connection Successful
 - if `Connect` message delivered and connection negotiated,
   - add set `node.Connected` to True
 
## Receive `Send` message
 - if sending `node` not on `Known_Nodes` list,
   - add `node` to `Known_Nodes` list
 - add received `Send.text` to `node.Previous_Messages`
 - trigger `Message_Received` notification

## Receive `SendPay` message
 - negotiate channel update
 - if current `SendPay.destination` is this node, then 
   if `node` that sent `SendPay` not on `Known_Nodes` list,
     - add `node` to `Known_Nodes` list
   - add received `SendPay.text` to `node.Previous_Messages`
   - queue private `Delivery_Receipt` message to `SendPay.last_hop`, 
    `delivery_receipt(preimage, hops=1)`
 - else select best `next_hop` node from `Known_Nodes` list,
   - decrement `SendPay.sats` field by `relay_cost` amount
   - queue updated `SendPay` message to `next_hop` node: `sendpay(SendPay.pubkey, SendPay.sats, SendPay.payment_hash, SendPay.payload, hops=1)`
- else if no connected node from `Known_Nodes` list is found,
   - queue private `Delivery_Failed` message to `SendPay.last_hop`: `delivery_failed(payment_hash, hops=1)`
 
## Receive `Delivery_Receipt` message
 - if `Delivery_Receipt.preimage` matches the `payment_hash` of an in-flight htlc for a channel,
   - negotiate new channel state crediting delivery
   - if this node generated the `preimage`,
     - mark the corresponding `Text.delivered` as true for entry in `node.Sent_Messages`

## Receive `Delivery_Failed` message
 - if `Delivery_Failed.payment_hash` matches in-flight htlc for a channel,
   - negotiate new channel state removing the in-flight htlc

## Receive `Create_Channel` message
 - negotiate opening transaction with `Create_Channel.node`

## Receive `Close_Channel` message
 - if unresolved in-flight htlcs with `Close_Channel.node`,
   - negotiate resolving in-flight htlcs
 - negotiate closing transaction
 
# Settings

## Connectivity
 - Pair Mesh Device
 - Mesh Region

## Wallet
 - fund LN wallet -> launch wallet app with BIP-21 payment intent
 - close all channels -> prompt for address to sweep funds

# Fragment 1: `Contacts` ViewGroup
List of nodes we have seen before and buttons to connect or chat with them.

## `Nodes_List` ListView
 - list of `Known_Nodes` with button to `Link/Unlink` and `Chat`
 - if `Last_Seen` field for node < 5 minutes, then
   - if node on `Connected` list, then `Unlink` button enabled
   - else, `Link` button enabled
 
 ### `Link` List Button
 - queue private `Create_Channel` message for selected `node`: `create_channel(pubkey, sats, hops=1)`
 
 ### `Unlink` List Item Button
 - queue private `Close_Channel` message for selected `node`: `close_channel(pubkey, hops=1)`
 
 ### `Chat` List Item Button
 - show `Fragment 2: Chat` 
 
 ## `Add` Floating Action Button
 - prompt for node GID and public key (scan QR code?)
 - add pubkey@gid to `Known_Nodes` list 
 
 # Fragment 2: `Chat` ViewGroup
 A conversation with another `node`.

 ## `Previous_Messages` ListView
  - list of `node.Received_Messages` and `node.Sent_Messages`, interleaved by timestamp
 
 ## `Text_Entry` TextBox
  - Box to enter "text" payload for `SendPay` message

 ## `Send` Button
 - if node `Last_Seen` time < 5 minutes OR `Connected` list is empty,
   - queue private `Send` message to `node` (no lightning payment), `send(GID, "text", hops=6)`
 - else,
   - generate `payment_hash` from hash of "text"
   - generate `payload` from encrypted "text"
   - queue private `SendPay` message to `node`: `sendpay(node.pubkey, sats, payment_hash, payload, hops=1)`