## ADDED Requirements

### Requirement: Initial Load with Page Size
The system SHALL load a maximum of 20 messages when switching to a conversation for the first time.

#### Scenario: Switch to conversation with more than 20 messages
- **WHEN** user switches to a conversation that has 60 messages
- **THEN** the frontend loads the 20 newest messages
- **AND** `hasMoreHistory` is set to `true`

#### Scenario: Switch to conversation with fewer than 20 messages
- **WHEN** user switches to a conversation that has 5 messages
- **THEN** the frontend loads all 5 messages
- **AND** `hasMoreHistory` is set to `false`

### Requirement: Scroll-to-Top Load More
The system SHALL load the next page of older messages when the user scrolls to the top of the message list.

#### Scenario: User scrolls to top and more messages exist
- **WHEN** message list scroll reaches top (`scrollTop < 50`)
- **AND** `hasMoreHistory` is `true`
- **AND** `isLoadingHistory` is `false`
- **THEN** system fetches the next 20 older messages using the cursor from the oldest currently loaded message
- **AND** prepends the new messages to the front of the current message list

#### Scenario: User scrolls to top but no more messages
- **WHEN** message list scroll reaches top
- **AND** `hasMoreHistory` is `false`
- **THEN** no request is made

#### Scenario: Scroll triggers while already loading
- **WHEN** message list scroll reaches top
- **AND** `isLoadingHistory` is `true`
- **THEN** the scroll event is ignored (no duplicate request)

### Requirement: Load More While Agent is Processing
The system SHALL NOT load more messages while an SSE stream is active.

#### Scenario: User scrolls to top during agent reply
- **WHEN** message list scroll reaches top
- **AND** `isProcessing` is `true`
- **THEN** no request is made

### Requirement: Scroll Position Preservation
The system SHALL preserve the user's scroll position after older messages are prepended to the list.

#### Scenario: Messages prepended while user is at the top
- **WHEN** load more completes and messages are prepended
- **THEN** the scroll position is adjusted so the previously visible top item remains in view

### Requirement: Cursor Tracking
The system SHALL use the `created_at` of the oldest currently loaded message as the cursor for the next page request.

#### Scenario: Load second page
- **WHEN** the first page loaded messages with the oldest `created_at` of `2026-06-10T12:00:00`
- **THEN** the load-more request uses `cursor=2026-06-10T12:00:00&limit=20`
