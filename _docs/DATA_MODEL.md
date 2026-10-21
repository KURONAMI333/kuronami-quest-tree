# Progress data model decisions

Decisions taken in the 2026-10-21 task/reward review. They override older notes where the two differ.

## Pickup tasks (review item 4)

`pickup` counts an item entity taken by a player. An item whose `owner` is the picking player does not count, so a
player cannot finish a pickup task by dropping and re-taking the same stack. This is the only anti-farming rule:
crafting an item and uncrafting it in a loop still counts, because the tree does not keep a per-player item history.
Pack authors who need more should use a `craft` or `consume` task instead.

## Reward blocking (review item 6)

`/quest_tree rewards block` stops the **pay-out of rewards**, not the quest or skill itself.

- Tasks, node completion, quest claims and taking a skill continue while rewards are blocked.
- A blocked reward is recorded as taken for its node and kept in the player's pending list. When the block is lifted,
  the pending rewards are paid in their original order.
- The block is judged per reward, including the rewards of the rank being taken (`rewardsOfRank`). `attribute`
  rewards are part of the skill effect and are always applied, even while pay-outs are blocked.
- `ignore_reward_blocking` / `ignore_blocking` still exempt one reward.

## Personal-node routing (review item 5)

The saved record keeps the set of node keys that were routed as personal. When the model cannot be read, or reads as
empty, that set is left alone: a migration is only applied for the keys the model actually shows, so an unreadable
pack file cannot move saved personal progress into the shared record.

## Currency rewards (review item 3)

A `currency` reward is paid through FTB Library's currency hook. Without an economy mod that registers a provider,
the reward is recorded as taken and pays nothing; the log says so once, and the validator warns pack authors on load.
Only a provider that is present but fails to pay keeps the reward unclaimed, so the node can be claimed again.

## Completion with unmet reward conditions (review item 9)

A reward whose `conditions` this player does not meet is skipped. It does not keep `allRewardsClaimed` false and does
not hold the node in `CLAIM`: the node finishes and, if repeatable, can repeat. The old note that such a reward stays
unclaimed forever is withdrawn; the reward is simply not offered to that player for that completion.

## Personal stages and team stages (review items 3 and 10)

`stages` is the player's copy of the stages the quest tree granted to them. Stage barriers open when the key is in
`stages` or in the shared `teamStages`. If it is in neither, both sides ask Pack Stages: the server checks the player's
saved stages, and the client reads the copy Pack Stages syncs to it. A stage granted by another mod therefore opens the
gate on both sides. A team stage this player was given is tracked in `teamStagesApplied`; on login only the stages the
team no longer holds are removed, so an offline removal is followed without stripping stages another mod granted.
